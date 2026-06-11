(ns apps.util.cache)

(defn cache-by-key
  "Creates a cache that stores results of (fetch-fn key) by key.
   Only non-nil results are cached, so a failed lookup will be retried on the
   next call. Returns a map with :lookup (fn [key] -> value-or-nil) and
   :invalidate (fn [key] -> nil) for removing a single entry."
  [fetch-fn]
  (let [store (atom {})]
    {:lookup
     (fn [key]
       (if (contains? @store key)
         (get @store key)
         (let [value (fetch-fn key)]
           (when (some? value)
             (swap! store assoc key value))
           value)))

     :invalidate
     (fn [key]
       (swap! store dissoc key)
       nil)}))

(defn ttl-cache
  "Creates a TTL-based cache around a zero-arg fetch function.
   The cached value expires after ttl-ms milliseconds. Both nil and non-nil
   results are cached (a nil result means 'no data' which is still valid).
   Returns a map with :lookup (fn [] -> value-or-nil) and
   :invalidate (fn [] -> nil) for clearing the cache."
  [fetch-fn ttl-ms]
  (let [store (atom {:value nil :fetched-at 0})]
    {:lookup
     (fn []
       (let [{:keys [value fetched-at]} @store
             now (System/currentTimeMillis)]
         (if (and (pos? fetched-at)
                  (< (- now fetched-at) ttl-ms))
           value
           (let [v (fetch-fn)]
             (reset! store {:value v :fetched-at now})
             v))))

     :invalidate
     (fn []
       (reset! store {:value nil :fetched-at 0})
       nil)}))
