(ns yetibot.core.test.adapters.discord-payload)

(defn status? [payload]
  ;; Answer cards are now V2 too. Ancillary transport tests distinguish the
  ;; unaccented working card, not the V2 flag shared by all responses.
  (and (= 32768 (:flags payload))
       (nil? (get-in payload [:components 0 :accent_color]))))

(defn logical [payload]
  ;; Ownership/deadline tests compare delivered text and stream semantics.
  ;; discord-components tests independently assert the actual V2 wire shape.
  (if (get-in payload [:components 0 :accent_color])
    (let [content (->> (get-in payload [:components 0 :components])
                       (filter #(= 10 (:type %))) (map :content) first)
          mentions (or (:allowed-mentions payload) (:allowed_mentions payload))]
      (cond-> (select-keys payload [:stream])
        content (assoc :content content)
        (= {:parse []} mentions)
        (assoc (if (contains? payload :allowed_mentions)
                 :allowed_mentions :allowed-mentions) mentions)))
    payload))
