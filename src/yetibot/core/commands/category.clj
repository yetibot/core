(ns yetibot.core.commands.category
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.room :as r]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.chat :as chat]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook] :as h]))

(def categories
  (into
    (sorted-map)
    {:img "returns an image url"
     :fun "generally fun and not work-related"
     :meme "returns a meme"
     :gif "returns a gif"
     :ci "continuous integration"
     :issue "issue tracker"
     :infra "infrastructure automation"
     :chart "returns a chart of some kind"
     :info "information lookups (e.g. wiki, wolfram, weather)"
     :repl "language REPLs"
     :util "utilities that help transform expressions or operate Yetibot"
     :crude "may return crude, racy and potentially NSFW results (e.g. urban)"
     :collection "operates on collections"
     :broken "known to be broken, probably due to an API that disappeared"
     :async "commands that execute asynchronously"}))

(defn show-all-cmd
  "category # show category names and descriptions and whether they are enabled or disabled"
  {:yb/cat #{:util}}
  [{:keys [chat-source]}]
  (let [disabled-cats (set (r/cat-settings-key (r/settings-for-chat-source chat-source)))]
    (for [[c desc] categories]
      (str
        (if (disabled-cats c) "🚫 " "✅ ")
        (name c) ": " desc))))

(defn valid-cat? [c] (get categories (keyword c)))

(defn set-cat
  "Disables or enables a category and returns a pair indicating [success msg]"
  [room category disabled?]
  (let [c (keyword category)]
    ; validate the category exists
    (if (get categories c)
      (do
        (r/apply-settings
          (a/uuid chat/*adapter*) room
          (fn [current-room-settings]
            (let [current-disabled (set (r/cat-settings-key current-room-settings))
                  f (if disabled? conj disj)]
              (assoc current-room-settings
                     r/cat-settings-key
                     (f current-disabled c)))))
        [true "success"])
      [false (str category
                  " is not a known category. Use `category names` to view the list.")])))

(defn disable-cat-cmd
  "category disable <category-name> # disable a category by name"
  {:yb/cat #{:util}}
  [{[_ c] :match cs :chat-source}]
  (let [[success? msg] (set-cat (:room cs) c true)]
    (if success? (str "Disabled " c) msg)))

(defn enable-cat-cmd
  "category enable <category-name> # enable a category by name"
  {:yb/cat #{:util}}
  [{[_ c] :match cs :chat-source}]
  (let [[success? msg] (set-cat (:room cs) c false)]
    (if success? (str "Enabled " c) msg)))

(defn category-list-cmd
  "category list <category-name> # list available commands in <category-name>"
  {:yb/cat #{:util}}
  [{[_ c] :match}]
  (if (valid-cat? c)
    (map (comp :doc meta) (h/cmds-for-cat c))
    (str c " is not a valid category. Use `category names` to view the list.")))

(defn category-names-cmd
  "category names  # list known category names"
  {:yb/cat #{:util}}
  [{[_ c] :match cs :chat-source}]
  (map name (keys categories)))


(cmd-hook #"category"
  #"disable\s+(\S+)" disable-cat-cmd
  #"enable\s+(\S+)" enable-cat-cmd
  #"list\s+(\S+)" category-list-cmd
  #"names" category-names-cmd
  _ show-all-cmd)

