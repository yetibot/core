(ns yetibot.core.adapters.discord-components
  "Discord-only presentation. No command execution or private provider data."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def accent-color 0x08B8CD)

(defn excerpt [text limit]
  (let [end (min (.length ^String text) limit)
        end (if (and (pos? end) (Character/isHighSurrogate (.charAt ^String text (dec end))))
              (dec end) end)
        prefix (subs text 0 end)
        paragraph (str/last-index-of prefix "\n\n")]
    (if (and (< end (count text)) paragraph (> paragraph (quot limit 2)))
      (subs prefix 0 paragraph) prefix)))

;; Combined Text Display budget for one Components V2 message.
(def max-text-length 4000)

(defn- next-fence [active line]
  (if-let [marker (second (re-find #"^ {0,3}(`{3,}|~{3,})" line))]
    (if active
      (if (and (= (first active) (first marker))
               (>= (count marker) (count active))
               (str/blank? (subs (str/trim line) (count marker))))
        nil active)
      marker)
    active))

(defn page-preview
  "Keep a Markdown code fence local to each page. Storage/download text is
  unchanged; continuation pages reopen the active fence without guessing a
  language. Callers bound source text and fence-marker length before paging."
  [pages page]
  (let [before (str/join "" (take page pages))
        body (nth pages page)
        active #(reduce next-fence nil (str/split-lines %))
        opening (active before)
        closing (active (str before body))]
    (str (when opening (str opening "\n")) body
         (when closing (str "\n" closing)))))

(defn- table-row [line]
  (when (and line (re-find #"(?<!\\)\|" line))
    (let [cells (mapv str/trim (str/split (str/trim line) #"(?<!\\)\|" -1))
          cells (if (str/blank? (first cells)) (subvec cells 1) cells)
          cells (if (and (seq cells) (str/blank? (peek cells))) (pop cells) cells)]
      (mapv #(str/replace % "\\|" "|") cells))))

(defn- aligned-table [rows separators]
  (when (and (every? #(= (count separators) (count %)) rows)
             (not-any? #(str/includes? % "```") (mapcat identity rows)))
    (let [widths (apply mapv max (mapv #(mapv count %) rows))
          ;; Check expansion before allocating padding: a short source table
          ;; with one long cell can otherwise grow quadratically.
          size (* (count rows) (+ 1 (reduce + widths) (* 2 (dec (count widths)))))]
      (when (<= (+ size 12) max-text-length)
        (str "```text\n"
             (str/join "\n"
               (map (fn [row]
                      (str/join "  "
                        (map-indexed
                          (fn [i cell]
                            (let [padding (apply str (repeat (- (nth widths i)
                                                               (count cell)) " "))]
                              (cond
                                (str/ends-with? (nth separators i) ":")
                                (str padding cell)
                                (= i (dec (count row))) cell
                                :else (str cell padding)))) row))) rows))
             "\n```")))))

(defn- format-tables [text]
  (let [lines (str/split text #"\n" -1)]
    (loop [i 0 output [] fence nil overflow? false]
      (if (= i (count lines))
        {:text (str/join "\n" output) :overflow? overflow?}
        (let [line (nth lines i)
              next-state (next-fence fence line)
              header (when-not (or fence next-state (re-find #"^(?: {4}|\t)" line))
                       (table-row line))
              separators (table-row (get lines (inc i)))]
          (if (and (seq header) (= (count header) (count separators))
                   (every? #(re-matches #":?-{3,}:?" %) separators))
            (let [end (loop [j (+ i 2)]
                        (if (and (< j (count lines)) (seq (table-row (nth lines j))))
                          (recur (inc j)) j))
                  rows (into [header] (map table-row (subvec lines (+ i 2) end)))
                  rendered (aligned-table rows separators)]
              (recur end (conj output (or rendered (str/join "\n" (subvec lines i end))))
                     fence (or overflow? (nil? rendered))))
            (recur (inc i) (conj output line) next-state overflow?)))))))

(defn answer-preview
  ([text] (answer-preview text 1800))
  ([text limit]
   (let [table (re-matcher #"(?m)^[^\n]*\|[^\n]*\n[ \t|:-]+\r?\n" text)
         prose (if (.find table) (subs text 0 (.start table)) text)
         preview (if (str/blank? prose) "This response contains a table."
                     (excerpt prose limit))
         fence (reduce next-fence nil (str/split-lines preview))]
     (cond-> preview fence (str "\n" fence)))))

(defn text-parts
  ([text] (text-parts text max-text-length))
  ([text budget]
   (if (empty? text)
     []
     (let [{rendered :text overflow? :overflow?}
           (when (<= (count text) budget) (format-tables text))]
       (if (or (nil? rendered) overflow? (> (count rendered) budget))
         [{:content (str (answer-preview text)
                         "\n\n-# Full answer attached as Markdown.")
           :file text :filename "answer.md"}]
         [{:content rendered}])))))

(defn- media-url [text]
  (when (and (string? text)
             (re-matches #"(?i)https?://[^\s<>]+\.(png|jpe?g|gif|webp|mp4|webm|mov)(?:\?[^\s<>]*)?" text))
    text))

(defn button-id [action [channel command]]
  (str "yb:" action ":" channel ":" command))

(defn parse-button [id]
  (when (string? id)
    (when-let [[_ action channel command token]
               (re-matches #"yb:(again|help|details|details-hide|confirm|data|data-hide|error):([^:]+):([^:]+)(?::([a-f0-9-]+))?" id)]
      (let [snapshot? (contains? #{"details" "details-hide" "data" "data-hide"}
                                 action)]
        (when (if snapshot?
                (or (and (contains? #{"details" "data"} action) (nil? token))
                    (and token (re-matches #"[a-f0-9]{16}" token)))
                (= (= action "confirm") (some? token)))
          (cond-> {:action action :key [channel command]
                   :confirmation (when-not snapshot? token)}
            (and snapshot? token) (assoc :token token)))))))

(defn payload
  "Build a V2 card. Details is a bounded caller-sanitized snapshot, never raw data."
  [{:keys [content image file filename command-key details details-token elapsed
           data? data-token error? allowed-mentions navigation]}]
  (let [media (or (when image (str "attachment://" filename)) (media-url content))
        buttons (when command-key
                  (mapv (fn [[action label]]
                          {:type 2 :style 2 :label label
                           :custom_id (str (button-id action command-key)
                                           (when-let [token (case action
                                                              "details" details-token
                                                              "data" data-token
                                                              nil)]
                                             (str ":" token)))})
                        (cond-> [["again" "Run again"] ["help" "Help"]]
                          (seq details) (conj ["details" "Show details"])
                          error? (conj ["error" "Error details"])
                          data? (conj ["data" "Explore data"]))))
        children (cond-> []
                   (and (not (str/blank? content)) (not= content media))
                   (conj {:type 10 :content content})
                   media (conj {:type 12 :items [{:media {:url media}}]})
                   file (conj {:type 13 :file {:url (str "attachment://" filename)}})
                   navigation (conj navigation)
                   elapsed (conj {:type 10 :content (str "-# " elapsed)})
                   (seq buttons) (conj {:type 1 :components buttons}))]
    {:flags 32768
     :components [{:type 17 :accent_color accent-color :components children}]
     :allowed-mentions (or allowed-mentions {:parse [] :replied_user false})}))

(defn inline-pane-payload
  "Insert a bounded public pane before the original footer/controls. Retain
  media/files without attachment edits; Hide uses the untouched JSON-only base."
  [base pane show-id hide-id hide-label]
  (when (and (= 1 (count (:components base)))
             (= 17 (get-in base [:components 0 :type])))
    (let [children (get-in base [:components 0 :components])
          maps #(filter map? (tree-seq coll? seq %))
          text-length #(reduce + 0 (keep (fn [c]
                                          (when (= 10 (:type c))
                                            (count (:content c)))) (maps %)))
          shorten? (> (+ (text-length children) (text-length pane))
                      max-text-length)
          children (mapv (fn [c]
                           (if (and shorten? (= 10 (:type c))
                                    (> (count (:content c)) 1400))
                             (update c :content
                               #(str (answer-preview % 650)
                                     "\n-# Answer shortened while this view is open. "
                                     hide-label " to restore it."))
                             c)) children)
          start (or (first (keep-indexed
                             (fn [i c]
                               (when (or (= 1 (:type c))
                                         (and (= 10 (:type c))
                                              (re-matches #"-# [0-9]+[.][0-9]s"
                                                          (:content c)))) i))
                             children))
                    (count children))
          children (into (into (conj (subvec children 0 start)
                                    {:type 14 :divider true :spacing 1}) pane)
                         (subvec children start))
          children (walk/postwalk
                     (fn [c]
                       (if (and (map? c) (= show-id (:custom_id c)))
                         (assoc c :label hide-label :custom_id hide-id) c))
                     children)
          result (assoc-in base [:components 0 :components] children)]
      (when (and (<= (text-length children) max-text-length)
                 (<= (count (filter :type (maps result))) 40))
        result))))

(defn inline-details-payload
  "Expand only frozen, caller-sanitized tool progress inside a public card."
  [base key token details]
  (when (and (string? details) (seq details))
    (inline-pane-payload
      base [{:type 10 :content (str "**Tool details**\n" (excerpt details 1800))}]
      (str (button-id "details" key) ":" token)
      (str (button-id "details-hide" key) ":" token) "Hide details")))

(defn message-text
  "Read visible text/media from legacy or V2 messages, excluding button labels."
  [message]
  (if-not (str/blank? (:content message))
    (:content message)
    (letfn [(texts [{:keys [type content components items]}]
              (case type
                10 [content]
                (9 17) (mapcat texts components)
                12 (keep #(get-in % [:media :url]) items)
                []))]
      (str/join "\n" (remove str/blank? (mapcat texts (:components message)))))))

(defn interaction-payload [text]
  (-> (payload {:content (if (> (count text) max-text-length)
                          (str (excerpt text 1800) "\n\n-# Preview shortened; send the command's help command for full documentation.")
                          text)})
      (dissoc :allowed-mentions)
      (assoc :allowed_mentions {:parse [] :replied_user false})))

(defn confirmation-payload [key token]
  (update-in
    (interaction-payload "Run the original command again? This may incur AI costs or repeat side effects.")
    [:components 0 :components] conj
    {:type 1 :components
     [{:type 2 :style 4 :label "Confirm rerun"
       :custom_id (str (button-id "confirm" key) ":" token)}]}))
