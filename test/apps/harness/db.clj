(ns apps.harness.db
  "Provides a throwaway PostgreSQL database for endpoint tests. The schema is built by applying the real migrations
   from the de-database repository, so schema changes reach the tests without a hand-maintained fixture image.

   The migrations come from the de-database commit recorded in test/contracts/sources.edn. They are downloaded the
   first time that commit is used and cached under target/de-database. See apps.harness.contracts for how to move
   the pin to a newer commit."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string])
  (:import
   (java.io File)
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.util.regex Pattern)
   (java.util.zip ZipInputStream)
   (org.testcontainers.postgresql PostgreSQLContainer)
   (org.testcontainers.utility DockerImageName)))

(def ^:private default-image "postgres:16-alpine")

(def ^:private sources-file "test/contracts/sources.edn")

(def ^:private cache-dir "target/de-database")

(def ^:private required-extensions ["uuid-ossp" "moddatetime" "btree_gist"])

(defn- getenv [name default]
  (or (System/getenv name) default))

(defn- pinned-source
  "Reads the de-database commit that the endpoint tests are pinned to."
  []
  (or (:de-database (edn/read-string (slurp sources-file)))
      (throw (ex-info (str "no :de-database entry in " sources-file) {:path sources-file}))))

(defn- extract-migrations!
  "Copies the files in the repository archive's migrations directory into `dir`. Every path in a GitHub archive
   starts with a directory named after the repository and commit, so that segment is ignored."
  [^ZipInputStream zip path ^File dir]
  (let [migration? (re-pattern (str "^[^/]+/" (Pattern/quote path) "/([^/]+)$"))]
    (loop [n 0]
      (if-let [entry (.getNextEntry zip)]
        (if-let [[_ file-name] (and (not (.isDirectory entry)) (re-find migration? (.getName entry)))]
          (do (io/copy zip (io/file dir file-name))
              (recur (inc n)))
          (recur n))
        n))))

(defn- download-migrations!
  "Downloads the migrations at the pinned commit into `dir`. They are extracted into a temporary directory that is
   renamed once it is complete, so an interrupted download can't leave a partial set of migrations in the cache."
  [{:keys [repo path commit]} ^File dir]
  (let [url (str "https://github.com/" repo "/archive/" commit ".zip")
        tmp (io/file (.getParentFile dir) (str (.getName dir) ".tmp-" (random-uuid)))]
    (println "Downloading de-database migrations at" commit)
    (.mkdirs tmp)
    (try
      (with-open [zip (ZipInputStream. (io/input-stream (io/as-url url)))]
        (when (zero? (extract-migrations! zip path tmp))
          (throw (ex-info (str "no files under " path " in " url) {:url url}))))
      (Files/move (.toPath tmp) (.toPath dir) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception e
        (run! #(.delete ^File %) (reverse (file-seq tmp)))
        (throw (ex-info (str "Unable to download the de-database migrations from " url ". Check the network "
                             "connection, or set DE_DATABASE_MIGRATIONS to the migrations directory of a local "
                             "de-database checkout.")
                        {:url url} e))))))

(defn- pinned-migrations-dir
  "Returns the cached migrations for the pinned commit, downloading them first if this is the first run to use it."
  []
  (let [{:keys [commit] :as source} (pinned-source)
        dir                         (io/file cache-dir commit "migrations")]
    (when-not (.isDirectory dir)
      (io/make-parents dir)
      (download-migrations! source dir))
    dir))

(defn- migrations-dir
  "Locates the de-database migrations. By default these are the migrations at the commit recorded in
   test/contracts/sources.edn, so that every machine builds the same schema and seed data. Set
   DE_DATABASE_MIGRATIONS to the migrations directory of a local checkout to test against migrations that haven't
   been merged yet."
  []
  (if-let [path (System/getenv "DE_DATABASE_MIGRATIONS")]
    (let [dir (io/file path)]
      (when-not (.isDirectory dir)
        (throw (ex-info (str "DE_DATABASE_MIGRATIONS is set to " path ", which is not a directory.") {:path path})))
      dir)
    (pinned-migrations-dir)))

(defn- up-migrations
  "Lists the up migrations in the order golang-migrate applies them. The version prefix is zero-padded, so a
   lexical sort is sufficient."
  [dir]
  (->> (.listFiles dir)
       (filter #(string/ends-with? (.getName %) ".up.sql"))
       (sort-by #(.getName %))))

(defn- migrate!
  "Applies each up migration as a single statement batch, which is what golang-migrate does."
  [db-spec dir]
  (doseq [ext required-extensions]
    (jdbc/execute! db-spec [(str "CREATE EXTENSION IF NOT EXISTS \"" ext "\"")]))
  (doseq [f (up-migrations dir)]
    (try
      (jdbc/execute! db-spec [(slurp f)] {:transaction? true})
      (catch Exception e
        (throw (ex-info (str "migration failed: " (.getName f)) {:file (.getPath f)} e))))))

(defn start!
  "Starts a PostgreSQL container, applies the migrations, and returns a map describing the connection."
  []
  (let [dir       (migrations-dir)
        image     (DockerImageName/parse (getenv "APPS_TEST_DB_IMAGE" default-image))
        container (doto (PostgreSQLContainer. image)
                    (.withDatabaseName "de")
                    (.withUsername "de")
                    (.withPassword "notprod")
                    (.start))
        db        {:container container
                   :host      (.getHost container)
                   :port      (.getMappedPort container (int 5432))
                   :name      "de"
                   :user      "de"
                   :password  "notprod"}]
    (migrate! {:connection-uri (format "jdbc:postgresql://%s:%d/de?user=de&password=notprod" (:host db) (:port db))}
              dir)
    db))

(defn stop! [{:keys [container]}]
  (when container (.stop container)))
