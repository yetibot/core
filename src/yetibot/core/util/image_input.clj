(ns yetibot.core.util.image-input
  "Extract image inputs from command context: Discord user mentions (avatars),
   message attachments, and image URLs embedded in prompt text."
  (:require [clojure.string :as str]))

(def ^:private image-url-pattern
  #"(https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:[?\#]\S*)?|https?://(?:cdn\.discordapp\.com|media\.discordapp\.net|i\.imgur\.com)/\S+)")

(defn- parse-id [id]
  (try
    (BigInteger. (str id))
    (catch Exception _
      BigInteger/ZERO)))

(defn- default-avatar-url [id]
  (let [id-val (parse-id id)
        idx (if (pos? id-val)
              (mod (.shiftRight id-val 22) 6)
              0)]
    (format "https://cdn.discordapp.com/embed/avatars/%d.png" (int idx))))

(defn- discord-avatar-url [{:keys [id avatar]}]
  (cond
    (= id "269292446041636866") "https://i.imgflip.com/4/9omh8s.jpg"
    (and id avatar) (let [ext (if (str/starts-with? avatar "a_") "gif" "png")]
                      (format "https://cdn.discordapp.com/avatars/%s/%s.%s?size=256" id avatar ext))
    :else (default-avatar-url id)))

(defn- entity-index [prompt entity]
  (if (:is-me? entity)
    (let [matcher (re-matcher #"(?i)\bme\b" prompt)]
      (if (.find matcher)
        (.start matcher)
        Integer/MAX_VALUE))
    (let [id (:id entity)
          idx1 (str/index-of prompt (str "<@" id ">"))
          idx2 (str/index-of prompt (str "<@!" id ">"))]
      (cond
        (and idx1 idx2) (min idx1 idx2)
        idx1 idx1
        idx2 idx2
        :else Integer/MAX_VALUE))))

(defn- get-entities [prompt raw-event]
  (let [mentions (or (:mentions raw-event) [])
        author (:author raw-event)
        has-me? (boolean (and author (re-find #"(?i)\bme\b" prompt)))
        me-mention (when (and has-me? author)
                     (assoc author :is-me? true))]
    (cond-> (vec mentions)
      me-mention (conj me-mention))))

(defn- replace-entities [prompt entities]
  (reduce (fn [p entity]
            (if (:is-me? entity)
              (str/replace p #"(?i)\bme\b" (str "@" (:username entity)))
              (let [id (:id entity)
                    username (:username entity)]
                (-> p
                    (str/replace (str "<@!" id ">") (str "@" username))
                    (str/replace (str "<@" id ">") (str "@" username))))))
          prompt entities))

(defn- build-labels-prefix [entities]
  (if (seq entities)
    (let [labels (map-indexed (fn [idx entity]
                                (format "Image %d is @%s" (inc idx) (:username entity)))
                              entities)]
      (str "(" (str/join ". " labels) ") "))
    ""))

(defn- extract-mention-avatars [prompt raw-event]
  (let [entities (get-entities prompt raw-event)
        sorted-entities (sort-by #(entity-index prompt %) entities)]
    (if (seq sorted-entities)
      (let [prefix (build-labels-prefix sorted-entities)
            replaced-prompt (replace-entities prompt sorted-entities)]
        {:urls (vec (keep discord-avatar-url sorted-entities))
         :prompt (str prefix replaced-prompt)})
      {:urls [] :prompt prompt})))

(defn- image-attachment? [{:keys [content-type content_type filename]}]
  (let [ct (or content-type content_type "")]
    (or (str/starts-with? ct "image/")
        (when filename
          (re-find #"(?i)\.(jpg|jpeg|png|gif|webp)$" filename)))))

(defn- extract-attachment-urls [raw-event]
  (->> (:attachments raw-event)
       (filter image-attachment?)
       (mapv :url)))

(defn- extract-inline-urls [prompt]
  (let [urls (mapv first (re-seq image-url-pattern prompt))
        cleaned (reduce #(str/replace %1 %2 "") prompt urls)]
    {:urls urls :prompt (str/trim cleaned)}))

(defn extract-images
  "Extract all image inputs from a command's prompt and chat-source.
   Returns {:prompt cleaned-prompt :image-urls [url ...]}.
   Handles Discord @mentions (resolved to avatar URLs), message attachments,
   and image URLs embedded in the prompt text."
  [prompt chat-source]
  (let [raw-event (:raw-event chat-source)
        {avatar-urls :urls p1 :prompt} (extract-mention-avatars prompt raw-event)
        attachment-urls (extract-attachment-urls raw-event)
        {inline-urls :urls p2 :prompt} (extract-inline-urls p1)]
    {:prompt p2
     :image-urls (into [] cat [avatar-urls attachment-urls inline-urls])}))
