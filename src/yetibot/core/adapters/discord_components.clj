(ns yetibot.core.adapters.discord-components
  "Discord-only presentation. No command execution or private provider data."
  (:require [clojure.string :as str]))

(def accent-color 0x08B8CD)

(defn excerpt [text limit]
  (let [end (min (.length ^String text) limit)
        end (if (and (pos? end) (Character/isHighSurrogate (.charAt ^String text (dec end))))
              (dec end) end)
        prefix (subs text 0 end)
        paragraph (str/last-index-of prefix "\n\n")]
    (if (and (< end (count text)) paragraph (> paragraph (quot limit 2)))
      (subs prefix 0 paragraph) prefix)))

(defn text-parts [text]
  (if (> (count text) 6000)
    [{:content (str (excerpt text 1800) "\n\n-# Full answer attached as Markdown.")
      :file text :filename "answer.md"}]
    (loop [start 0 parts []]
      (if (= start (count text))
        parts
        (let [end (min (+ start 2000) (count text))
              end (if (and (< end (count text))
                           (Character/isHighSurrogate (.charAt ^String text (dec end))))
                    (dec end) end)]
          (recur end (conj parts {:content (subs text start end)})))))))

(defn- media-url [text]
  (when (and (string? text)
             (re-matches #"(?i)https?://[^\s<>]+\.(png|jpe?g|gif|webp|mp4|webm|mov)(?:\?[^\s<>]*)?" text))
    text))

(defn button-id [action [channel command]]
  (str "yb:" action ":" channel ":" command))

(defn parse-button [id]
  (when (string? id)
    (when-let [[_ action channel command token]
               (re-matches #"yb:(again|help|details|confirm):([^:]+):([^:]+)(?::([a-f0-9-]+))?" id)]
      (when (= (= action "confirm") (some? token))
        {:action action :key [channel command] :confirmation token}))))

(defn payload
  "Build a V2 card. Details is a bounded caller-sanitized snapshot, never raw data."
  [{:keys [content image file filename command-key details allowed-mentions]}]
  (let [media (or (when image (str "attachment://" filename)) (media-url content))
        buttons (when command-key
                  (mapv (fn [[action label]]
                          {:type 2 :style 2 :label label
                           :custom_id (button-id action command-key)})
                        (cond-> [["again" "Run again"] ["help" "Help"]]
                          (seq details) (conj ["details" "Show details"]))))
        children (cond-> []
                   (and (not (str/blank? content)) (not= content media))
                   (conj {:type 10 :content content})
                   media (conj {:type 12 :items [{:media {:url media}}]})
                   file (conj {:type 13 :file {:url (str "attachment://" filename)}})
                   (seq buttons) (conj {:type 1 :components buttons}))]
    {:flags 32768
     :components [{:type 17 :accent_color accent-color :components children}]
     :allowed-mentions (or allowed-mentions {:parse [] :replied_user false})}))

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

(defn private-payload [text]
  (-> (payload {:content (if (> (count text) 2000)
                          (str (excerpt text 1800) "\n\n-# Preview shortened; send the command's help command for full documentation.")
                          text)})
      (assoc :flags (+ 32768 64))
      (dissoc :allowed-mentions)
      (assoc :allowed_mentions {:parse [] :replied_user false})))

(defn confirmation-payload [key token]
  (update-in
    (private-payload "Run the original command again? This may incur AI costs or repeat side effects.")
    [:components 0 :components] conj
    {:type 1 :components
     [{:type 2 :style 4 :label "Confirm rerun"
       :custom_id (str (button-id "confirm" key) ":" token)}]}))
