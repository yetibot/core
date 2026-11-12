(ns yetibot.core.util.agent-execution
  "Optional host-only execution seam. Core has no app dependency.

   context creates a live capability, never a DTO accepted from EDN/HTTP/model
   results. Bind *context* for inherited work. Its callbacks receive the actual
   authorized handler input: :start, :execute-command, :admit-inference,
   :generation-limit [] -> positive cap <=262144 (no fresh default).
   :admit-inference [safe-wire-attrs] -> nonnil own handle before each send;
   :admit-command [input] -> nonnil own handle before uncovered derived dispatch;
   :finish-operation [handle {:state :definitive|:unknown :result optional}]
   finishes ONLY that handle, never its ancestors. Command callbacks are needed
   when derived commands occur. Bind *operation* to an already admitted outer
   tool/tick handle to avoid charging interpreter components a second time;
   nested sends still get separate admissions. Futures must convey bindings.
   Child callbacks must clear direct-input ownership; core never creates it.

   install-host! [owner callbacks] installs one bridge (same owner may reload).
   :classify-agent [input origin] returns :human-literal, :legacy-scheduled,
   or a live context; everything else fails closed. :command-origin [dispatch]
   may identify actual app leading-control contributions, never bless inherited
   command identity. :write-cron [tx existing entity origin] owns grant codec and
   allocation within the caller transaction. :tick [fresh-row] owns restored
   identity, transactional tick claim, final reply/checkpoint and uncertainty.
   :resume [row] is invoked only by explicit resume-inherited! [rows] after host
   readiness; the host must validate/claim safe rows, not replay unknown state.
   root-template [] -> {:ast original-tree :text original-input} and dynamic
   *command-template* -> {:ast stage-tree :text evaluated-before-pipe-substitution}
   are evaluator-owned observations, NOT adapter authentication. The host must
   compare its real event/capture and use actual leading-control parsing.
   :command-origin receives both templates plus actual [start end family] spans.
   :write-cron returns classified SQL attrs; use db.cron/prepare-write! inside
   the native writer's existing transaction and persist before commit/wiring.
   Tick hosts must call claim-tick! to reload/lock again and restore real identity
   before effects; callback arguments alone never prove a current fence.

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
(def ^:dynamic *command-template* nil)
(defn root-template [] (when *scope* @(:root-template *scope*)))
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
   :root-template (atom nil)
   :aliases (atom {}) :closers (atom {}) :closed? (atom false)})
(defn close-scope! [scope]
  (when scope
    (let [closers (locking scope
                    (when (compare-and-set! (:closed? scope) false true)
                      (vec (vals @(:closers scope)))))
          errors (atom [])]
      (doseq [f closers]
        (try (f) (catch Throwable e (swap! errors conj e))))
      (when-let [error (first @errors)] (throw error)))))
(defn with-scope* [f]
  (if *scope* (f)
      (let [scope (new-scope)]
        (binding [*scope* scope]
          (try (f) (finally (close-scope! scope)))))))
(defn on-close! [key f]
  (when-not *scope* (throw (ex-info "No owning expression scope." {})))
  (let [run-now? (locking *scope*
                   (when-not (contains? @(:closers *scope*) key)
                     (swap! (:closers *scope*) assoc key f)
                     @(:closed? *scope*)))]
    (when run-now? (f))))
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
  (when-let [origin (or *origin* (origin-of result)
                       (some #(or (origin-of %)
                                  (some (fn [[_ _ family]] family) (contributions %)))
                             sources)
                       *value-origin*)]
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
  (let [text (String. (str value))]
    (mark-text! text (when origin [[0 (count text) origin]]))))
(defn substitute [text pattern replacement]
  ;; appendReplacement preserves the formatter's existing $group/backslash
  ;; semantics. Track the actual substituted segment, not a fabricated body.
  (let [matcher (re-matcher pattern text)
        buffer (StringBuffer.)]
    (loop [last 0 parts []]
      (if (.find matcher)
        (let [before (.length buffer)
              _ (.appendReplacement matcher buffer (str replacement))
              start (+ before (- (.start matcher) last))
              inserted (.substring buffer start)
              inserted (if (= inserted (str replacement))
                         (mark-text! inserted (contributions replacement))
                         (operand inserted
                           (some #(nth % 2) (contributions replacement))))]
          (recur (.end matcher)
                 (conj parts (subtext text last (.start matcher)) inserted)))
        (join-text (conj parts (subtext text last (count text))))))))
(defn substituted-value! [value result]
  (if-let [origin (origin-of result)]
    (mark-output! (if (string? value) (operand value origin) value) origin)
    value))
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
              (f {:text text :input input :root-template (root-template)
                  :command-template *command-template*
                  :contributions (contributions text)
                  :value-origin *value-origin*}))))))

(defn with-command* [text input f]
  (let [origin (dispatch-origin text input)]
    (when (and *scope* @(:closed? *scope*)
               (or origin *value-origin* (host-active?)))
      (throw (ex-info "Owning expression is closed; no further effects permitted." {})))
    (binding [*origin* origin]
      (if (and origin (not *operation*))
        (let [handle (invoke! origin :admit-command input)]
          (when-not handle
            (throw (ex-info "Inherited command admission was denied." {})))
          (binding [*operation* handle]
            (try
              (let [result (f)]
                (invoke! origin :finish-operation handle {:state :definitive :result result})
                (forward! result))
              (catch Throwable e
                (invoke! origin :finish-operation handle {:state :unknown})
                (throw e)))))
        (forward! (f))))))
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
            (and (nil? origin) (#{:human-literal :legacy-scheduled} route))
            (and (nil? origin) (not (host-active?)) (= :legacy route)))
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
  (when *scope* (compare-and-set! (:root-template *scope*) nil {:ast ast :text text}))
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
