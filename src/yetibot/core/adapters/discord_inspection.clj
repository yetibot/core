(ns yetibot.core.adapters.discord-inspection
  "Bounded opt-in public snapshots and read-only Discord navigation."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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

(defn capture! [state {:keys [data? error? public-data? public-data public-error]}]
  (swap! state
    (fn [s]
      (if (:sealed? s)
        s
        (let [s (-> s (update :data? #(or % data?))
                       (update :error? #(or % error?)))]
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
    (when-let [[_ channel message token kind node page]
               (re-matches
                 #"yb:view:([0-9]{1,20}):([0-9]{1,20}):([a-f0-9]{16}):([de]):([0-9]{1,4}):([0-9]{1,2})"
                 id)]
      {:action "view" :key [channel message] :token token :kind kind
       :node (Long/parseLong node) :page (Long/parseLong page)})))

(defn- summary [v]
  (cond
    (map? v) (str "object · " (count v) " fields")
    (vector? v) (str "array · " (count v) " items")
    (string? v) (components/excerpt v 80)
    :else v))

(defn- navigation-button [label key token kind node page]
  {:type 2 :style 2 :label label
   :custom_id (control-id key token kind node page)})

(defn payload
  "Return nil for malformed navigation. Only retained public snapshots are read.
  Node IDs/selected values never select arbitrary paths or invoke commands."
  [state key kind node-id page selected]
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
            shortened? (> (count encoded) 2700)
            title (if (= kind "e") "Error details" "Explore data")
            path (str (if (seq path) (str/join " › " path) "Overview")
                      (when (seq children)
                        (str " · " (inc (* 8 page)) "–"
                             (+ (* 8 page) (count visible))
                             " of " (count children))))
            content (str "**" title "**\n"
                         (str/replace (components/excerpt path 160) "`" "ˋ")
                         "\n\n```json\n" (components/excerpt encoded 2700) "\n```"
                         (when shortened? "\nPreview shortened; open a field to inspect it.")
                         (when (= kind "e")
                           "\nOnly retained public diagnostics are shown. Raw responses and private data were not retained for this view.")
                         (when (and (= kind "d") (empty? (:data s)))
                           "\nOther result data is not automatically public.")
                         "\n-# Bounded public snapshot · up to 8 results · large values shortened.")
            token (:token s)
            base (-> (components/payload {:content content :elapsed (:elapsed s)})
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
