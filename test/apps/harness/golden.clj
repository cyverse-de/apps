(ns apps.harness.golden
  "Golden-file comparisons for large response bodies. Values that legitimately change from run to run (UUIDs,
   timestamps, the random suffixes from apps.harness.system/unique-name) are replaced with stable placeholders, and
   the result is compared with a checked-in EDN file.

   When a response is supposed to change, regenerate the files and review the diff:

     UPDATE_GOLDEN=1 lein test :endpoint"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.walk :as walk]))

(def ^:private golden-dir "test/golden")

(def ^:private uuid-re #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
(def ^:private timestamp-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z")
(def ^:private unique-suffix-re #"-[0-9a-f]{8}\b")

(defn- sorted
  "Converts every map to a sorted map so that the placeholder numbering and the file contents are deterministic."
  [x]
  (walk/postwalk #(if (map? %) (into (sorted-map) %) %) x))

(defn normalize
  "Replaces volatile values with placeholders. Each distinct UUID gets its own numbered placeholder, so the golden
   file still shows which fields refer to the same entity."
  [body]
  (let [ids (atom {})
        id  (fn [u] (or (@ids u) ((swap! ids assoc u (str "<uuid-" (inc (count @ids)) ">")) u)))]
    (walk/prewalk (fn [x]
                    (if (string? x)
                      (-> x
                          (.replaceAll (str timestamp-re) "<timestamp>")
                          (as-> s (reduce (fn [s u] (.replace ^String s ^String u ^String (id u)))
                                          s
                                          (re-seq uuid-re s)))
                          (.replaceAll (str unique-suffix-re) "-<unique>"))
                      x))
                  (sorted body))))

(defn- golden-file [name]
  (io/file golden-dir (str name ".edn")))

(defn- write! [f data]
  (io/make-parents f)
  (with-open [w (io/writer f)]
    (binding [*out* w] (pprint data))))

(defn check
  "Returns [expected actual] for use in an `is` form. A missing golden file is written and treated as a match, so the
   first run of a new test creates its file; review and commit it like any other change."
  [name body]
  (let [f      (golden-file name)
        actual (normalize body)]
    (when (or (System/getenv "UPDATE_GOLDEN") (not (.exists f)))
      (write! f actual))
    [(read-string (slurp f)) actual]))
