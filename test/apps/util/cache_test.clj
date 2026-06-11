(ns apps.util.cache-test
  (:require
   [apps.util.cache :as cache]
   [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; cache-by-key tests
;; ---------------------------------------------------------------------------

(deftest cache-by-key-caches-non-nil-results
  (testing "A non-nil result is cached and the fetch function is not called again"
    (let [call-count (atom 0)
          fetch-fn   (fn [k] (swap! call-count inc) {:id k :name "workspace"})
          c          (cache/cache-by-key fetch-fn)
          lookup     (:lookup c)]
      (is (= {:id "alice" :name "workspace"} (lookup "alice")))
      (is (= 1 @call-count))
      ;; Second call should use cache
      (is (= {:id "alice" :name "workspace"} (lookup "alice")))
      (is (= 1 @call-count)))))

(deftest cache-by-key-does-not-cache-nil
  (testing "A nil result is not cached, so subsequent calls retry the fetch"
    (let [call-count (atom 0)
          results    (atom {})
          fetch-fn   (fn [k] (swap! call-count inc) (get @results k))
          c          (cache/cache-by-key fetch-fn)
          lookup     (:lookup c)]
      ;; First call returns nil (user doesn't exist yet)
      (is (nil? (lookup "bob")))
      (is (= 1 @call-count))
      ;; Second call still retries because nil was not cached
      (is (nil? (lookup "bob")))
      (is (= 2 @call-count))
      ;; Now the user exists
      (swap! results assoc "bob" {:id "bob" :name "workspace"})
      (is (= {:id "bob" :name "workspace"} (lookup "bob")))
      (is (= 3 @call-count))
      ;; Now it's cached
      (is (= {:id "bob" :name "workspace"} (lookup "bob")))
      (is (= 3 @call-count)))))

(deftest cache-by-key-caches-per-key
  (testing "Different keys are cached independently"
    (let [call-count (atom 0)
          fetch-fn   (fn [k] (swap! call-count inc) {:id k})
          c          (cache/cache-by-key fetch-fn)
          lookup     (:lookup c)]
      (is (= {:id "alice"} (lookup "alice")))
      (is (= {:id "bob"} (lookup "bob")))
      (is (= 2 @call-count))
      ;; Both cached independently
      (is (= {:id "alice"} (lookup "alice")))
      (is (= {:id "bob"} (lookup "bob")))
      (is (= 2 @call-count)))))

(deftest cache-by-key-invalidate-removes-entry
  (testing "Invalidating a key causes the next lookup to re-fetch"
    (let [call-count (atom 0)
          fetch-fn   (fn [k] (swap! call-count inc) {:id k :v @call-count})
          c          (cache/cache-by-key fetch-fn)
          lookup     (:lookup c)
          invalidate (:invalidate c)]
      (is (= {:id "alice" :v 1} (lookup "alice")))
      (is (= 1 @call-count))
      ;; Invalidate
      (invalidate "alice")
      ;; Next lookup re-fetches
      (is (= {:id "alice" :v 2} (lookup "alice")))
      (is (= 2 @call-count)))))

(deftest cache-by-key-propagates-exceptions
  (testing "Exceptions from fetch-fn propagate without caching"
    (let [call-count (atom 0)
          fetch-fn   (fn [k]
                       (swap! call-count inc)
                       (throw (ex-info "DB error" {:key k})))
          c          (cache/cache-by-key fetch-fn)
          lookup     (:lookup c)]
      ;; First call throws
      (is (thrown-with-msg? Exception #"DB error" (lookup "alice")))
      (is (= 1 @call-count))
      ;; Second call retries (nothing was cached)
      (is (thrown-with-msg? Exception #"DB error" (lookup "alice")))
      (is (= 2 @call-count)))))

(deftest cache-by-key-caches-falsey-values
  (testing "A non-nil falsey value (false) is cached correctly"
    (let [call-count (atom 0)
          fetch-fn   (fn [_k] (swap! call-count inc) false)
          c          (cache/cache-by-key fetch-fn)
          lookup     (:lookup c)]
      ;; false is non-nil, so it should be cached
      (is (= false (lookup "flag")))
      (is (= 1 @call-count))
      ;; Second call uses cache
      (is (= false (lookup "flag")))
      (is (= 1 @call-count)))))

;; ---------------------------------------------------------------------------
;; ttl-cache tests
;; ---------------------------------------------------------------------------

(deftest ttl-cache-caches-result
  (testing "The fetch function is called once and the result is cached"
    (let [call-count (atom 0)
          fetch-fn   (fn [] (swap! call-count inc) "v1.0")
          c          (cache/ttl-cache fetch-fn 60000)
          lookup     (:lookup c)]
      (is (= "v1.0" (lookup)))
      (is (= 1 @call-count))
      ;; Cached
      (is (= "v1.0" (lookup)))
      (is (= 1 @call-count)))))

(deftest ttl-cache-caches-nil-result
  (testing "A nil result is cached (nil is a valid value, not a missing entry)"
    (let [call-count (atom 0)
          fetch-fn   (fn [] (swap! call-count inc) nil)
          c          (cache/ttl-cache fetch-fn 60000)
          lookup     (:lookup c)]
      (is (nil? (lookup)))
      (is (= 1 @call-count))
      ;; nil is cached — no re-fetch
      (is (nil? (lookup)))
      (is (= 1 @call-count)))))

(deftest ttl-cache-expires-after-ttl
  (testing "The cached value expires after the TTL and is re-fetched"
    (let [call-count (atom 0)
          fetch-fn   (fn [] (swap! call-count inc) (str "v" @call-count))
          ;; Use a 50ms TTL for testing
          c          (cache/ttl-cache fetch-fn 50)
          lookup     (:lookup c)]
      (is (= "v1" (lookup)))
      (is (= 1 @call-count))
      ;; Still cached immediately
      (is (= "v1" (lookup)))
      (is (= 1 @call-count))
      ;; Wait for expiry
      (Thread/sleep 60)
      ;; Should re-fetch
      (is (= "v2" (lookup)))
      (is (= 2 @call-count)))))

(deftest ttl-cache-invalidate-forces-refetch
  (testing "Invalidation causes immediate re-fetch on next lookup"
    (let [call-count (atom 0)
          fetch-fn   (fn [] (swap! call-count inc) (str "v" @call-count))
          c          (cache/ttl-cache fetch-fn 60000)
          lookup     (:lookup c)
          invalidate (:invalidate c)]
      (is (= "v1" (lookup)))
      (is (= 1 @call-count))
      ;; Invalidate
      (invalidate)
      ;; Re-fetches immediately
      (is (= "v2" (lookup)))
      (is (= 2 @call-count)))))
