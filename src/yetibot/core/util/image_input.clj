(ns yetibot.core.util.image-input
  "Extract image inputs from command context: Discord user mentions (avatars),
   message attachments, and image URLs embedded in prompt text."
  (:require [clojure.string :as str]))

(def ^:private image-url-pattern
  #"(https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:[?\#]\S*)?|https?://(?:cdn\.discordapp\.com|media\.discordapp\.net|i\.imgur\.com)/\S+)")

(defn- discord-avatar-url [{:keys [id avatar]}]
  (when (and id avatar)
    (let [ext (if (str/starts-with? avatar "a_") "gif" "png")]
      (format "https://cdn.discordapp.com/avatars/%s/%s.%s?size=256" id avatar ext))))

(defn- replace-mentions [prompt mentions]
  (reduce (fn [p {:keys [id username]}]
            (-> p
                (str/replace (str "<@!" id ">") (str "@" username))
                (str/replace (str "<@" id ">") (str "@" username))))
          prompt mentions))

(defn- extract-mention-avatars [prompt raw-event]
  (if-let [mentions (seq (:mentions raw-event))]
    {:urls (vec (keep discord-avatar-url mentions))
     :prompt (replace-mentions prompt mentions)}
    {:urls [] :prompt prompt}))

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
