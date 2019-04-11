(ns yetibot.core.commands.karma.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::name string?)
(s/def ::id string?)
(s/def ::user (s/keys :req-un [::name ::id]))

(s/def ::ctx (s/keys :req-un [::user]))

(s/def ::user-id (s/and string? #(re-matches #"@\w[-\w]*\w" %)))
(s/def ::action (s/and string? (s/or :positive #(= "++" %)
                                     :negative #(= "--" %))))
(s/def ::note (s/nilable string?))


;;
;; run-time data validators
;;

;; get-score
(s/def :karma.get-score/match (s/and vector?
                                     (s/cat :_ string?
                                            :user-id ::user-id)))
(s/def ::get-score-ctx
  (s/merge ::ctx (s/keys :req-un [:karma.get-score/match])))

;; adjust-score
(s/def :karma.adjust-score/match (s/and vector?
                                        (s/cat :_ string?
                                               :user-id ::user-id
                                               :action ::action
                                               :note (s/? ::note))))
(s/def ::adjust-score-ctx
  (s/merge ::ctx (s/keys :req-un [:karma.adjust-score/match])))
