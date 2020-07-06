(ns yetibot.core.commands.category
  (:require
    [yetibot.core.util.command :refer [command-enabled?]]
    [yetibot.core.models.channel :as c]
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
  [{{:keys [room uuid]} :chat-source}]
  ;; Optimization note: this could be pulled out of `settings` key from fn args
  ;; map instead of looking it up directly from the db:
  (let [disabled-cats (c/get-disabled-cats uuid room)]
    (for [[c desc] categories]
      (str
        (if (disabled-cats c) "🚫 " "✅ ")
        (name c) ": " desc))))

(defn valid-cat? [c] (get categories (keyword c)))

(defn set-cat
  "Disables or enables a category and returns a pair indicating [success msg]"
  [uuid channel category disabled?]
  (let [c (keyword category)]
    ;; validate the category exists
    (if (get categories c)
      (let [existing-disabled-cats (c/get-disabled-cats uuid channel)]
        (c/set-disabled-cats
          uuid
          channel
          (if disabled?
            (conj existing-disabled-cats c)
            (disj existing-disabled-cats c)))
        [true "success"])
      [false
       (str
         category
         " is not a known category. Use `category names` to view the list.")])))

(defn disable-cat-cmd
  "category disable <category-name> # disable a category by name"
  {:yb/cat #{:util}}
  [{[_ c] :match cs :chat-source}]
  (let [[success? msg] (set-cat (:uuid cs) (:room cs) c true)]
    (if success?
      {:result/value (str "✓ Disabled " c " category")}
      {:result/error msg})))

;; Note: if you disable `util` commands you won't be able to set categories any
;; more, thus locking yourself out and requiring manually editing the db to
;; re-enable.
(defn enable-cat-cmd
  "category enable <category-name> # enable a category by name"
  {:yb/cat #{:util}}
  [{[_ c] :match cs :chat-source}]
  (let [[success? msg] (set-cat (:uuid cs) (:room cs) c false)]
    (if success?
      {:result/value (str "✓ Enabled " c " category")}
      {:result/error msg})))

(defn category-list-cmd
  "category list <category-name> # list available commands in <category-name>"
  {:yb/cat #{:util}}
  [{[_ c] :match}]
  (if (valid-cat? c)
    {:result/value (sort (map (comp :doc meta) (h/cmds-for-cat c)))}
    {:result/error (str c " is not a valid category. Use `category names` to view the list.")}))

(defn category-names-cmd
  "category names  # list known category names"
  {:yb/cat #{:util}}
  [{[_ c] :match cs :chat-source}]
  {:result/value (map name (keys categories))
   :result/data categories})

(cmd-hook #"category"
  #"disable\s+(\S+)" disable-cat-cmd
  #"enable\s+(\S+)" enable-cat-cmd
  #"list\s+(\S+)" category-list-cmd
  #"names" category-names-cmd
  _ show-all-cmd)

(comment
  (show-all-cmd {})
  (category-list-cmd {:match [nil "util"]})
  )


