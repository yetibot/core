(ns yetibot.core.test.util.spec-config
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.util.spec-config :refer :all]
    [clojure.test :refer :all]))

(def test-config
  {:db {:url "postgresql://localhost:5432/yetibot"
        :table {:prefix "yetibot_"}}})

(s/def ::prefix string?)
(s/def ::table (s/keys :req-un [::prefix]))
(s/def ::url string?)
(s/def ::db (s/keys :req-un [::url ::table]))
(s/def ::test-config (s/keys :req-un [::db]))

(deftest test-get-config

  (testing "An invalid spec"
    (let [{:keys [error]} (get-config test-config ::prefix [:db])]
      (is
        (= error :invalid-shape))))

  (testing "A valid spec"
    (is (= (get-config test-config ::db [:db])
           {:value {:url "postgresql://localhost:5432/yetibot"
                    :table {:prefix "yetibot_"}}})))


  (testing "Spec assumptions"

    (is (= (s/conform ::test-config {})
           :clojure.spec.alpha/invalid))

    (is
      (=
       (with-out-str
         (s/explain ::test-config {:db 1})))
      "1 - failed: map? in: [:db] at: [:db] spec: :yetibot.core.test.util.spec-config/db\n"
        )))


(deftest master-spec-building

  (s/spec? (s/get-spec :yetibot.config.spec/default-command))

  (reduce
    (fn [acc [path spec]]
      ;; gen specs here
      )
    {}
    {:weather {:weatherbitio {:key "xoxb" :default {:zip "98104"}}}
     :command {:prefix "!"}}
    )


  ;; Example nested map data structure for which we want a spec.
  ;;
  ;; The goal is to build up a spec for this dynamically given some partial
  ;; specs and a data structure that provides paths to the fragments those specs
  ;; represent in the nested map:
  {:weather {:weatherbitio {:key "xoxb" :default {:zip "98104"}}}
   :command {:prefix "!"}}

  ;; We have a few specs that describe the fragments at:
  ;; - [:weather :weatherbitio]
  ;; - [:command :prefix]
  (s/def :yetibot.config.spec.weatherbitio/key :string?)
  (s/def :yetibot.config.spec.weatherbitio.default/zip string?)
  (s/def :yetibot.config.spec.weatherbitio/default
    (s/keys :req-un [:yetibot.config.spec.weatherbitio.default/zip]))
  (s/def :yetibot.config.spec/weatherbitio
    (s/keys :req-un [:yetibot.config.spec.weatherbitio/key
                     :yetibot.config.spec.weatherbitio/default]))

  (s/def :yetibot.config.spec.command/prefix string?)

  ;; And a data structure representing paths to fragments of the map that we
  ;; want to spec:
  {[:weather :weatherbitio] :yetibot.config.spec/weatherbitio
   [:command :prefix] :yetibot.config.spec.command/prefix}

  ;; We essentially want to complete the spec, generating something like:
  (s/def :yetibot.config.spec/weather
    (s/keys :req-un [:yetibot.config.spec/weatherbitio]))
  (s/def :yetibot.config.spec/command
    (s/keys :req-un [:yetibot.config.spec.command/prefix]))

  ;; The final thing we wanted:
  (s/def :yetibot.config/spec
    (s/keys :req-un [:yetibot.config.spec/weather
                     :yetibot.config.spec/command]))

  ;; I'm keeping things arbitrarily 'simple' in this example - in reality the
  ;; map data structure is quite a bit wider and deeper. Notice the amount of
  ;; 'places' we had to deal with to spec a pretty small structure.
  ;;
  ;; What am I doing wrong?


    {[:default :command] :yetibot.config.spec/default-command,
     [:command :prefix] :yetibot.config.spec/command-prefix}







  )
