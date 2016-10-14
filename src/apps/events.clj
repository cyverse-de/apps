(ns apps.events
  (:require [clojure.tools.logging :as log]
            [apps.util.config :as cfg]
            [apps.amqp :as amqp]
            [langohr.basic :as lb])
  (:import [org.cyverse.events.ping PingMessages$Pong]
           [com.google.protobuf.util JsonFormat]))

(defn exchange-config
  []
  {:name        (cfg/exchange-name)
   :durable     (cfg/exchange-durable?)
   :auto-delete (cfg/exchange-auto-delete?)})

(defn queue-config
  []
  {:name        (cfg/queue-name)
   :durable     (cfg/queue-durable?)
   :auto-delete (cfg/queue-auto-delete?)})

(defn ping-handler
  [channel {:keys [delivery-tag routing-key]} msg]
  (lb/ack channel delivery-tag)
  (log/info (format "[events/ping-handler] [%s] [%s]" routing-key (String. msg)))
  (lb/publish channel (cfg/exchange-name) "events.apps.pong"
    (.print (JsonFormat/printer)
      (.. (PingMessages$Pong/newBuilder)
        (setPongFrom "apps")
        (build)))))
