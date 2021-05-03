(ns yetibot.core.commands.alias
  (:require
    [clojure.set :refer [difference]]
    [taoensso.timbre :refer [info]]
    [clojure.string :as s]
    [yetibot.core.util.format :refer [pseudo-format-n *subst-prefix*
                                      remove-surrounding-quotes]]
    [yetibot.core.handler :refer [record-and-run-raw]]
    [yetibot.core.util.command :as command]
    [yetibot.core.models.help :as help]
    [yetibot.core.db.alias :as model]
    [yetibot.core.hooks :refer [cmd-hook cmd-unhook]]))

(def method-like-replacement-prefix "\\$")

(defn- build-alias-cmd-fn [cmd]
  (fn [{:keys [user args yetibot-user]}]
    (let [args (if (empty? args) [] (s/split args #" "))
          ;; this binding must be constrained to just computing the expr so it
          ;; doesn't interfere with regular/default substitution in the
          ;; evaluation of piped expressions (i.e. %s instead of $s)
          expr (binding [*subst-prefix* method-like-replacement-prefix]
                 (str command/config-prefix (pseudo-format-n cmd args)))
          results (record-and-run-raw expr user yetibot-user
                                      ;; avoid double recording the yetibot
                                      ;; response since the parent command
                                      ;; execution that evaluated the alias
                                      ;; will record the nested response
                                      {:record-yetibot-response? false})]
      (first (map :result results)))))

(defn- existing-alias [cmd-name]
  (first (model/query {:where/map {:cmd-name cmd-name}})))

(defn- cleaned-cmd-name [a-name]
  ; allow spaces in a-name, even though we just grab the first word to use as
  ; the actual cmd
  (-> a-name s/trim (s/split #" ") first))

(defn- wire-alias
  "Example input (use quotes to make it a literal so it doesn't get evaluated):
   i90 = \"random | echo http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg\"
   Alias args are also supported (all args inserted):
   alias grid = \"repeat 10 `repeat 10 $s | join`\"
   Use first arg only:
   alias sayhi = echo hi, $1"
  [{:keys [cmd-name cmd]}]
  (let [docstring (str cmd-name " # alias for " cmd)
        existing-alias (existing-alias cmd-name)
        cmd-fn (build-alias-cmd-fn cmd)]
    (cmd-hook (re-pattern cmd-name)
      _ cmd-fn)
    ;; manually add docs since the meta on cmd-fn is lost in cmd-hook
    (help/add-docs cmd-name [docstring] true)
    (info "wire-alias" existing-alias)
    (if existing-alias
      (format "Replaced existing alias %s. Was `%s`" cmd-name (:cmd existing-alias))
      (format "%s alias created" cmd-name))))

(defn add-alias [{:keys [cmd-name cmd user-id] :as alias-info}]
  (let [new-alias-map {:user-id user-id :cmd-name cmd-name :cmd cmd}]
    (info "adding alias with" new-alias-map)
    (info "existing" (existing-alias cmd-name))
    (if-let [{id :id} (existing-alias cmd-name)]
      (model/update-where {:id id} new-alias-map)
      (model/create new-alias-map)))
  alias-info)

(defn load-aliases []
  (let [alias-cmds (model/find-all)]
    (run! #(wire-alias %) alias-cmds)))

(defn- built-in? [cmd]
  ;; subtract known aliases from every command registered in `help`
  ((difference
    (set (keys (help/get-docs)))
    (set (map :cmd-name (model/find-all))))
   ;; if the command is in the resulting set it's a built-in
   cmd))

(defn create-alias
  "alias <alias> = \"<cmd>\" # alias a cmd, where <cmd> is a normal command expression. Note the use of quotes, which treats the right-hand side as a literal allowing the use of pipes. Use $s as a placeholder for all args, or $n (where n is a 1-based index of which arg) as a placeholder for a specific arg."
  {:yb/cat #{:util}}
  [{[_ a-name a-cmd] :match user :user}]
  (if user
    (let [cmd-name (cleaned-cmd-name a-name)]
      (info "create alias" a-name a-cmd "user:" user)
      (if (built-in? cmd-name)
        (str "Can not alias existing built-in command " a-name)
        (let [cmd (remove-surrounding-quotes a-cmd)
              alias-map {:user-id (:username user) :cmd-name cmd-name :cmd cmd}
              ;; get wire-alias response before `add-alias` to determine whether
              ;; it was updated or created
              response (wire-alias alias-map)]
          (add-alias alias-map)
          response)))
    {:result/error
     (str "Oops, I don't know who you are 😱. This is probably a bug:"
          "Yetibot should know who everyone is.")}))

(defn list-aliases
  "alias # show existing aliases"
  {:yb/cat #{:util}}
  [_]
  (let [as (model/find-all)]
    (if (empty? as)
      {:result/error "No aliases have been defined"}
      {:result/value
       (into {} (map (juxt :cmd-name :cmd) as))
       :result/data as})))

(defn remove-alias
  "alias remove <alias> # remove alias by name"
  {:yb/cat #{:util}}
  [{[_ cmd] :match}]
  (if-let [{:keys [id]} (existing-alias cmd)]
    (do
      (model/delete id)
      (cmd-unhook cmd)
      {:result/value (format "Alias %s removed" cmd)
       :result/data {:id id :cmd cmd}})
    {:result/error
     (format "Could not find alias %s." cmd)}))

(defonce loader (future (load-aliases)))

(comment

  ;; load the first alias only
  (let [alias-cmds (model/find-all)
        first-alias (first alias-cmds)]
    (info "loading" (pr-str first-alias))
    (wire-alias first-alias))

  )


(cmd-hook #"alias"
          #"^$" list-aliases
          #"remove\s+(\w+)" remove-alias
          #"([\S\s]+?)\s*\=\s*(.+)" create-alias)
