(ns yetibot.core.adapters.discord-inspection
  "Bounded opt-in public snapshots and read-only Discord navigation."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [yetibot.core.adapters.discord-components :as components]))

(defn initial []
  {:token (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 16)
   :data? false :error? false :sealed? false :data [] :errors []
   :budget {:data {:nodes 192 :chars 8000}
            :errors {:nodes 64 :chars 4000}}})

(defn- public-key [k]
  (when (or (keyword? k) (string? k) (integer? k))
    (let [s (if (keyword? k) (name k) (str k))]
      (when-not (re-find
                  #"(?i)(secret|token|password|credential|authorization|cookie|header|api[-_]?key|private|prompt|reasoning|arguments|tool[-_]?(result|trace)|^messages$|^request$|^response$|^raw$)"
                  s)
        (components/excerpt (str/replace s #"[\p{Cntrl}]" " ") 64)))))

(defn- bounded [value budget]
  (let [left (atom budget)]
    (letfn [(room? [] (and (pos? (:nodes @left)) (pos? (:chars @left))))
            (walk [v depth]
              (swap! left update :nodes dec)
              (cond
                (> depth 6) "[snapshot shortened]"
                (nil? v) nil
                (boolean? v) v
                (number? v) (if (or (Double/isNaN (double v))
                                   (Double/isInfinite (double v)))
                              "[unavailable]" v)
                (string? v)
                (let [s (if (re-find
                              #"(?i)(bearer\s+\S+|(?:sk-|xai-|ghp_)[A-Za-z0-9_-]{16,})"
                              v)
                          "[redacted]" v)
                      s (components/excerpt
                          (str/replace s #"[\p{Cntrl}&&[^\n\t]]" " ")
                          (min 1200 (:chars @left)))]
                  (swap! left update :chars - (count s)) s)
                (map? v)
                (reduce
                  (fn [acc [k item]]
                    (if-not (room?)
                      (reduced acc)
                      (if-let [k (public-key k)]
                        (do (swap! left update :chars
                                   #(max 0 (- % (count k))))
                            (if (room?)
                              (assoc acc k (walk item (inc depth)))
                              (reduced acc)))
                        acc)))
                  (array-map) (take 32 v))
                (sequential? v)
                (reduce (fn [acc item]
                          (if (room?) (conj acc (walk item (inc depth)))
                              (reduced acc)))
                        [] (take 32 v))
                :else "[unavailable]"))]
      {:value (walk value 0) :budget @left})))

(defn- text-pages [text]
  ;; Larger documents still get the complete Markdown attachment, not a clipped
  ;; snapshot. Retain at most one 256-Ki UTF-16 public document per command.
  (when (and (string? text) (< 3968 (count text) 262145)
             ;; A metadata safety bound can leave an unpaired surrogate. The
             ;; excerpt splitter must never loop on an empty final prefix.
             (.canEncode (.newEncoder java.nio.charset.StandardCharsets/UTF_8)
                         ^CharSequence text)
             ;; Reopening/closing fences must fit inside the message budget.
             (not (re-find #"(?m)^ {0,3}(?:`{65,}|~{65,})" text)))
    {:text text
     :pages
     (loop [remaining text pages []]
       (if (empty? remaining)
         pages
         (let [prefix (components/excerpt remaining 3000)
               newline (str/last-index-of prefix "\n")
               end (if (and newline (> newline 1500))
                     (inc newline) (count prefix))]
           (recur (subs remaining end)
                  (conj pages (subs remaining 0 end))))))}))

(defn capture! [state {:keys [data? error? public-data? public-data public-error
                            public-text]}]
  (swap! state
    (fn [s]
      (if (:sealed? s)
        s
        (let [s (cond-> (-> s (update :data? #(or % data?))
                             (update :error? #(or % error?)))
                  public-text (assoc :text-pages (text-pages public-text)))]
        (reduce
          (fn [s [field present? value]]
            (let [budget (get-in s [:budget field])]
              (if (and present? (< (count (get s field)) 8)
                       (pos? (:nodes budget)) (pos? (:chars budget)))
                (let [{:keys [value budget]} (bounded value budget)]
                  (-> s (assoc-in [:budget field] budget)
                      (update field conj value)))
                s)))
          s [[:data public-data? public-data]
             [:errors (and error? (or public-error (empty? (:errors s))))
              (or public-error
                  {:diagnostics "Additional details were not retained."})]]))))))

(defn- root-value [s kind]
  (let [values (get s (if (= kind "d") :data :errors))]
    (case (count values)
      0 {"snapshot" "Reviewed public fields were not retained for this command."}
      1 (first values)
      values)))

(defn- tree [value]
  (let [nodes (atom [])]
    (letfn [(visit [v parent path]
              (let [id (count @nodes)
                    entries (cond (map? v) (seq v)
                                  (vector? v) (map-indexed vector v)
                                  :else nil)]
                (swap! nodes conj {:value v :parent parent :path path})
                (let [children
                      (mapv (fn [[k v]]
                              [(str k) (visit v id (conj path (str k)))])
                            entries)]
                  (swap! nodes assoc-in [id :children] children))
                id))]
      (visit value nil [])
      @nodes)))

(defn- control-id [key token kind node page]
  (str "yb:view:" (str/join ":" key) ":" token ":" kind ":" node ":" page))

(defn parse-control [id]
  (when (and (string? id) (<= (count id) 100))
    (or
      (when-let [[_ channel message token page]
                 (re-matches
                   #"yb:page:([0-9]{1,20}):([0-9]{1,20}):([a-f0-9]{16}):([0-9]{1,3})"
                   id)]
        {:action "page" :key [channel message] :token token
         :page (Long/parseLong page)})
      (when-let [[_ channel message token _operation depth page]
                 (re-matches
                   #"yb:tree:([0-9]{1,20}):([0-9]{1,20}):([a-f0-9]{16}):([ecpn]):([1-8]):([0-9]{1,2})"
                   id)]
        {:action "tree" :key [channel message] :token token
         :depth (Long/parseLong depth) :page (Long/parseLong page)})
      (when-let [[_ channel message token kind node page]
                 (re-matches
                   #"yb:view:([0-9]{1,20}):([0-9]{1,20}):([a-f0-9]{16}):([de]):([0-9]{1,4}):([0-9]{1,2})"
                   id)]
        {:action "view" :key [channel message] :token token :kind kind
         :node (Long/parseLong node) :page (Long/parseLong page)})
      (when-let [[_ channel message token]
                 (re-matches
                   #"yb:error-hide:([0-9]{1,20}):([0-9]{1,20}):([a-f0-9]{16})" id)]
        {:action "error-hide" :key [channel message] :token token}))))

(defn- text-page [state key page]
  (let [{:keys [token sealed? text-pages]} @state
        pages (:pages text-pages)]
    (when (and sealed? (integer? page) (<= 0 page) (< page (count pages)))
      {:content (str (components/page-preview pages page)
                     "\n\n-# Page " (inc page) " of "
                     (count pages) " · Full answer: answer.md")
       :navigation
       {:type 1 :components
        (mapv (fn [[label target disabled?]]
                {:type 2 :style 2 :label label :disabled disabled?
                 :custom_id (str "yb:page:" (str/join ":" key) ":" token ":"
                                 target)})
              [["Previous" (max 0 (dec page)) (zero? page)]
               ["Next" (min (dec (count pages)) (inc page))
                (= page (dec (count pages)))]])}})))

(defn text-page-options
  "Only page the exact reviewed final output. Transformed pipeline values,
  unreviewed outputs and oversized documents keep ordinary rendering."
  [state key source]
  (when (and (string? source) (= source (get-in @state [:text-pages :text])))
    (text-page state key 0)))

(defn inline-text-payload [state key base page]
  (when (and (= 10 (get-in base [:components 0 :components 0 :type]))
             (some (fn [node]
                     (when (map? node)
                       (let [control (parse-control (:custom_id node))]
                         (and (= "page" (:action control))
                              (= key (:key control))
                              (= (:token @state) (:token control))))))
                   (tree-seq coll? seq base)))
    (when-let [{:keys [content navigation]} (text-page state key page)]
      (-> base
          (assoc-in [:components 0 :components 0 :content] content)
          (update-in [:components 0 :components]
            (fn [children]
              (mapv (fn [c]
                      (if (some #(= "page" (:action
                                             (parse-control (:custom_id %))))
                                (:components c))
                        navigation c)) children)))))))

(defn- summary [v]
  (cond
    (map? v) (str "object · " (count v)
                  (if (= 1 (count v)) " field" " fields"))
    (vector? v) (str "array · " (count v)
                     (if (= 1 (count v)) " item" " items"))
    (string? v) (components/excerpt v 80)
    :else v))

(defn- navigation-button [label key token kind node page]
  {:type 2 :style 2 :label label
   :custom_id (control-id key token kind node page)})

(defn- view-payload
  [state key kind node-id page selected
   {:keys [preview-limit inline?] :or {preview-limit 2700}}]
  (let [s @state
        nodes (tree (root-value s kind))
        current (get nodes node-id)
        current-children (vec (take 8 (drop (* 8 page) (:children current))))
        selection (when (and (vector? selected) (= 1 (count selected)))
                    (some (fn [[_ id]] (when (= (str id) (first selected)) id))
                          current-children))
        valid? (and (:sealed? s) current (<= 0 page)
                    (<= page (quot (max 0 (dec (count (:children current)))) 8))
                    (or (nil? selected) selection))
        node-id (or selection node-id)
        page (if selection 0 page)
        {:keys [value parent path children]} (get nodes node-id)]
    (when valid?
      (let [visible (vec (take 8 (drop (* 8 page) children)))
            preview (if (seq children)
                      (into (array-map)
                            (map (fn [[label id]]
                                   [label (summary (:value (get nodes id)))]))
                            visible)
                      value)
            encoded (-> (with-out-str (json/pprint preview :escape-unicode false))
                        (str/replace "`" "\\u0060"))
            shortened? (> (count encoded) preview-limit)
            title (if (= kind "e") "Error details" "Explore data")
            path (str (if (seq path) (str/join " › " path) "Overview")
                      (when (seq children)
                        (str " · " (inc (* 8 page)) "–"
                             (+ (* 8 page) (count visible))
                             " of " (count children))))
            content (str "**" title "**\n"
                         (str/replace (components/excerpt path 160) "`" "ˋ")
                         "\n\n```json\n" (components/excerpt encoded preview-limit) "\n```"
                         (when shortened? "\nPreview shortened; open a field to inspect it.")
                         (when (= kind "e")
                           "\nOnly retained public diagnostics are shown. Raw responses and private data were not retained for this view.")
                         (when (and (= kind "d") (empty? (:data s)))
                           "\nOther result data is not automatically public.")
                         "\n-# Bounded public snapshot · up to 8 results · large values shortened.")
            token (:token s)
            base (-> (components/payload
                       {:content content :elapsed (when-not inline? (:elapsed s))})
                     (dissoc :allowed-mentions)
                     (assoc :allowed_mentions {:parse [] :replied_user false}))
            rows (cond-> []
                   (seq visible)
                   (conj {:type 1 :components
                          [{:type 3 :min_values 1 :max_values 1
                            :placeholder "Open a field or item"
                            :custom_id (control-id key token kind node-id page)
                            :options
                            (mapv (fn [[label id]]
                                    {:label (if (str/blank? label) "Unnamed field"
                                                (components/excerpt label 100))
                                     :description
                                     (let [v (:value (get nodes id))
                                           description (str (summary v))]
                                       (cond
                                         (nil? v) "null"
                                         (str/blank? description) "empty string"
                                         :else (components/excerpt description 100)))
                                     :value (str id)}) visible)}]})
                   (or parent (pos? page) (> (count children) (* 8 (inc page))))
                   (conj {:type 1 :components
                          (cond-> []
                            parent
                            (conj (navigation-button "Back" key token kind parent 0))
                            (pos? page)
                            (conj (navigation-button "Previous" key token kind
                                                     node-id (dec page)))
                            (> (count children) (* 8 (inc page)))
                            (conj (navigation-button "Next" key token kind
                                                     node-id (inc page))))}))]
        (update-in base [:components 0 :components] into rows)))))

(defn payload
  "Return nil for malformed navigation. Only retained public snapshots are read.
  Node IDs/selected values never select arbitrary paths or invoke commands."
  [state key kind node-id page selected]
  (view-payload state key kind node-id page selected {}))

(defn- depth-preview [value depth]
  (cond
    (and (zero? depth) (coll? value) (seq value)) (summary value)
    (map? value) (into (empty value)
                      (map (fn [[k v]] [k (depth-preview v (dec depth))])) value)
    (vector? value) (mapv #(depth-preview % (dec depth)) value)
    :else value))

(defn- json-pages [encoded]
  (loop [remaining encoded pages []]
    (if (empty? remaining)
      pages
      (let [prefix (components/excerpt remaining 1500)
            newline (str/last-index-of prefix "\n")
            end (if (and (> (count remaining) 1500) newline (> newline 750))
                  (inc newline) (count prefix))]
        (recur (subs remaining end) (conj pages (subs remaining 0 end)))))))

(defn inline-data-payload
  "Show the frozen public tree at up to two levels initially. Depth/page
  controls are stateless and bounded; no paths, providers or commands run."
  [state key base depth page]
  (let [s @state value (root-value s "d")
        max-depth (reduce max 1 (map #(count (:path %)) (tree value)))
        depth (or depth (min 2 max-depth))
        page (or page 0)]
    (when (and (:sealed? s) (:data? s) (integer? depth)
               (<= 1 depth max-depth) (integer? page) (<= 0 page 99))
      (let [encoded (-> (with-out-str
                         (json/pprint (depth-preview value depth)
                                      :escape-unicode false))
                        (str/replace "`" "\\u0060"))
            pages (json-pages encoded)
            token (:token s)
            button (fn [label operation level page disabled?]
                     {:type 2 :style 2 :label label :disabled disabled?
                      :custom_id (str "yb:tree:" (str/join ":" key) ":" token
                                      ":" operation ":" level ":" page)})]
        (when-let [preview (get pages page)]
          (components/inline-pane-payload
            base
            [{:type 10 :content
              (str "**Explore data**\nDepth " depth " · Page " (inc page)
                   " of " (count pages) "\n\n```json\n" preview "\n```"
                   "\n-# Retained public snapshot · collapsed branches show type/count."
                   " Large values may be shortened.")}
             {:type 1 :components
              (cond-> [(button "Collapse" "c" (max 1 (dec depth)) 0 (= depth 1))
                       (button "Expand" "e" (min max-depth (inc depth)) 0
                               (= depth max-depth))]
                (> (count pages) 1)
                (conj (button "Previous" "p" depth (max 0 (dec page)) (zero? page))
                      (button "Next" "n" depth (min (dec (count pages)) (inc page))
                              (= page (dec (count pages))))))}]
            (str (components/button-id "data" key) ":" token)
            (str (components/button-id "data-hide" key) ":" token) "Hide data"))))))

(defn inline-error-payload
  "Expand reviewed diagnostics inside a retained public reply. The base is
  JSON-only outbound presentation, not raw command/provider data. Never upload
  files or clear attachments; collapsing restores the exact original card."
  [state key base node page selected]
  (when-let [pane (view-payload state key "e" node page selected
                          {:preview-limit 1600 :inline? true})]
    (when (and (= 1 (count (:components base)))
               (= 17 (get-in base [:components 0 :type])))
      (let [children (get-in base [:components 0 :components])
            elapsed-index
            (last (keep-indexed
                    (fn [i c]
                      (when (and (= 10 (:type c))
                                 (re-matches #"-# [0-9]+[.][0-9]s"
                                             (or (:content c) ""))) i)) children))
            elapsed (when elapsed-index (get children elapsed-index))
            children
            (into []
              (keep-indexed
                (fn [i c]
                  (when (not= i elapsed-index)
                    (if (and (= 10 (:type c)) (> (count (:content c)) 1400))
                      ;; Leave room for even a long closing Markdown fence.
                      (update c :content
                        #(str (components/answer-preview % 650)
                              "\n-# Error message shortened while details are open."
                              " Hide details to restore it."))
                      c))) children))
            hide-id (str "yb:error-hide:" (str/join ":" key) ":" (:token @state))
            children (walk/postwalk
                       (fn [c]
                         (if (and (map? c)
                                  (= (components/button-id "error" key)
                                     (:custom_id c)))
                           (assoc c :label "Hide error details" :custom_id hide-id)
                           c)) children)
            children (cond-> (into (conj children {:type 14 :divider true :spacing 1})
                                    (get-in pane [:components 0 :components]))
                       elapsed (conj elapsed))
            result (assoc-in base [:components 0 :components] children)
            nodes (filter map? (tree-seq coll? seq result))]
        (when (and (<= (reduce + 0 (keep #(when (= 10 (:type %))
                                           (count (:content %))) nodes))
                       components/max-text-length)
                   (<= (count (filter :type nodes)) 40))
          result)))))
