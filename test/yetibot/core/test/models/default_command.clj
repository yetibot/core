(ns yetibot.core.test.models.default-command
  (:require [yetibot.core.models.default-command :as dc]
            [midje.sweet :refer [=> fact]]))

(fact "see what happens when i introduce this new fact"
      (dc/configured-default-command)
      true => true)
