(ns yetibot.core.util.agent-execution
  "Optional host-only execution seam. Core has no app dependency.

   context creates a live capability, never a DTO accepted from EDN/HTTP/model
   results. Bind *context* for inherited work. Its callbacks receive the actual
   authorized handler input: :start, :execute-command, :admit-inference,
   :generation-limit (zero args), and optionally :admit-command/:finish-operation.
   Child callbacks must clear direct-input ownership; core never creates it.

   install-host! [owner callbacks] installs one bridge (same owner may reload).
   :classify-agent [input origin] returns :human-literal, :legacy-scheduled,
   or a live context; everything else fails closed. :command-origin [dispatch]
   may identify actual app leading-control contributions, never bless inherited
   command identity. :write-cron [tx existing entity origin] owns grant codec and
   allocation within the caller transaction. :tick [fresh-row] owns restored
   identity, transactional tick claim, final reply/checkpoint and uncertainty.
   :resume [row] is invoked only by explicit resume-inherited! after host ready.

   with-scope* owns an evaluation-local identity sidecar, shared by nested
   evaluation and bound-fn/futures. mark-output! [result family] attaches a
   source family without public keys/metadata; on-close! [key f] releases the
   family once at expression exit/timeout. Closing never erases its marker.
   contributions [string] returns private [start end family] executable operand
   spans for the host's real control parser. mark-output! tracks values, while
   text spans track only actual substitutions, not arbitrary data taint."
  (:require [clojure.string :as string])
  (:import [java.util IdentityHashMap]))

(deftype ^:private Context [callbacks live])
(def ^:dynamic *context* nil)
(def ^:dynamic *origin* nil)
(def ^:dynamic *value-origin* nil)
(def ^:dynamic *operation* nil)
(deftype ^:private SchedulerOrigin [kind id])
(def ^:dynamic *scheduler-origin* nil)
(defn scheduler-origin []
  (when (instance? SchedulerOrigin *scheduler-origin*)
    {:kind (.-kind ^SchedulerOrigin *scheduler-origin*)
     :id (.-id ^SchedulerOrigin *scheduler-origin*)}))
(defn with-scheduler* [kind id f]
  (binding [*scheduler-origin* (SchedulerOrigin. kind id)] (f)))
(def ^:dynamic *scope* nil)
(defonce ^:private host (atom nil))

(defn context [callbacks]
  (when-not (every? #(fn? (get callbacks %))
                   [:start :execute-command :admit-inference :generation-limit])
    (throw (ex-info "Invalid host execution callbacks." {})))
  (Context. callbacks (atom true)))

(defn capability? [x] (instance? Context x))
(defn close! [^Context c]
  (when (capability? c) (reset! (.-live c) false)))
(defn invoke! [c action & args]
  (when-not (and (capability? c) @(.-live ^Context c))
    (throw (ex-info "Inherited execution capability is missing or closed." {})))
  (if-let [f (get (.-callbacks ^Context c) action)]
    (apply f args)
    (throw (ex-info "Inherited execution callback is unavailable." {:action action}))))

(defn install-host! [owner callbacks]
  (when-not (and owner (map? callbacks))
    (throw (ex-info "Invalid execution host." {})))
  (swap! host (fn [old]
                (when (and old (not= owner (:owner old)))
                  (throw (ex-info "An execution host is already installed." {})))
                {:owner owner :callbacks callbacks}))
  nil)
(defn host-active? [] (some? @host))
(defn host-callback [key] (get-in @host [:callbacks key]))

(defn new-scope []
  {:values (IdentityHashMap.) :text (IdentityHashMap.)
   :templates (IdentityHashMap.)
   :aliases (atom {}) :closers (atom {}) :closed? (atom false)})
(defn close-scope! [scope]
  (when (and scope (compare-and-set! (:closed? scope) false true))
    (doseq [f (vals @(:closers scope))] (f))))
(defn with-scope* [f]
  (if *scope* (f)
      (let [scope (new-scope)]
        (binding [*scope* scope]
          (try (f) (finally (close-scope! scope)))))))
(defn on-close! [key f]
  (when-not *scope* (throw (ex-info "No owning expression scope." {})))
  (locking *scope*
    (if @(:closed? *scope*) (f)
        (swap! (:closers *scope*) #(if (contains? % key) % (assoc % key f))))))
(defn- side-get [key value]
  (when *scope* (locking *scope* (.get ^IdentityHashMap (key *scope*) value))))
(defn- side-put! [key value origin]
  (when (and *scope* (some? value) origin)
    (locking *scope* (.put ^IdentityHashMap (key *scope*) value origin)))
  value)
(defn origin-of [value] (side-get :values value))
(defn mark-output! [value origin] (side-put! :values value origin))
(defn contributions [text] (or (side-get :text text) []))
(defn mark-text! [text spans] (side-put! :text text (seq spans)))
(defn value-origin [] (or *origin* *context* *value-origin*))
(defn forward! [result & sources]
  (when-let [origin (or *origin* (origin-of result) (some origin-of sources) *value-origin*)]
    (mark-output! result origin))
  result)

(defn join-text [values]
  (let [values (vec values)
        result (apply str values)
        [_ spans] (reduce (fn [[offset spans] value]
                            [(+ offset (count (str value)))
                             (into spans (map (fn [[a b o]]
                                                [(+ offset a) (+ offset b) o])
                                              (contributions value)))])
                          [0 []] values)]
    (mark-text! result spans)))
(defn subtext [text start end]
  (mark-text! (subs text start end)
    (keep (fn [[a b o]]
            (when (< (max start a) (min end b))
              [(- (max start a) start) (- (min end b) start) o]))
          (contributions text))))
(defn operand [value origin]
  (let [text (str value)]
    (mark-text! text (when origin [[0 (count text) origin]]))))
(defn substitute [text pattern replacement]
  (let [matcher (re-matcher pattern text)]
    (loop [last 0 parts []]
      (if (.find matcher)
        (recur (.end matcher)
               (conj parts (subtext text last (.start matcher)) replacement))
        (join-text (conj parts (subtext text last (count text))))))))
(defn substituted-value! [value result]
  (when-let [origin (origin-of result)]
    (mark-output! value origin)
    (when (string? value) (operand value origin)))
  value)
(defn remember-alias! [name]
  (when (and *scope* (value-origin))
    (swap! (:aliases *scope*) assoc name (value-origin))))
(defn alias-origin [name]
  (when *scope* (get @(:aliases *scope*) name)))

(defn contributed-origin [text start end]
  (some (fn [[a b origin]] (when (< (max a start) (min b end)) origin))
        (contributions text)))
(defn dispatch-origin
  "Called after command permissions, before effects. Identity and agent action
   attribution are core-owned; actual leading AI controls are host-owned."
  [text input]
  (or *origin* *context* (alias-origin (:cmd input))
      (let [m (re-matcher #"\S+" text)
            tokens (loop [v []]
                     (if (.find m) (recur (conj v [(.start m) (.end m)])) v))
            [a b] (first tokens)
            [c d] (second tokens)]
        (or (when a (contributed-origin text a b))
            (when (and (= "agent" (:cmd input)) c)
              (contributed-origin text c d))
            (when-let [f (host-callback :command-origin)]
              (f {:text text :input input :contributions (contributions text)
                  :value-origin *value-origin*}))))))

(defn with-command* [text input f]
  (let [origin (dispatch-origin text input)]
    (binding [*origin* origin]
      (if (and origin (not *operation*))
        (let [handle (invoke! origin :admit-command input)]
          (binding [*operation* handle]
            (try
              (let [result (f)]
                (invoke! origin :finish-operation handle {:state :definitive :result result})
                result)
              (catch Throwable e
                (invoke! origin :finish-operation handle {:state :unknown})
                (throw e)))))
        (f)))))
(defn agent-route [input]
  (let [origin (or *origin* *context*)
        route (cond origin origin
                    (#{:legacy-unclassified :compatibility-unclassified}
                      (:kind (scheduler-origin))) :legacy-scheduled
                    (= :human-literal (:kind (scheduler-origin))) :human-literal
                    (host-active?) (when-let [f (host-callback :classify-agent)]
                                     (f input origin))
                    :else :legacy)]
    (if (or (capability? route)
            (#{:legacy :human-literal :legacy-scheduled} route))
      route
      (throw (ex-info "Agent origin has no live host grant." {})))))
(defn generation-limit []
  (when-let [origin (or *origin* *context*)]
    (let [cap (invoke! origin :generation-limit)]
      (when-not (and (integer? cap) (<= 1 cap 262144))
        (throw (ex-info "Invalid inherited generation limit." {})))
      cap)))
(defn resume-inherited! [rows]
  (when-let [resume (host-callback :resume)]
    (doseq [row rows :when (= "app-inherited-v1" (:execution-kind row))]
      (resume row))))

(defn register-template! [ast text]
  (when (seq (contributions text))
    (doseq [node (tree-seq vector? rest ast) :when (vector? node)]
      (let [{:instaparse.gll/keys [start-index end-index]} (meta node)]
        (when (and start-index end-index)
          (side-put! :templates node (subtext text start-index end-index))))))
  ast)
(defn template-result! [node result]
  (if-let [template (side-get :templates node)]
    (if (= template result) (mark-text! result (contributions template)) result)
    result))
