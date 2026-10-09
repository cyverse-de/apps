(ns apps.harness.db
  "Provides a throwaway PostgreSQL database for endpoint tests. The schema is built by applying the real migrations
   from the de-database repository, so schema changes reach the tests without a hand-maintained fixture image."
  (:require
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string])
  (:import
   (org.testcontainers.postgresql PostgreSQLContainer)
   (org.testcontainers.utility DockerImageName)))

(def ^:private default-image "postgres:16-alpine")

(def ^:private required-extensions ["uuid-ossp" "moddatetime" "btree_gist"])

(defn- getenv [name default]
  (or (System/getenv name) default))

(defn- migrations-dir
  "Locates the de-database migrations. DE_DATABASE_MIGRATIONS wins; otherwise a sibling checkout of de-database is
   assumed, matching the usual layout of a directory containing all of the DE repositories."
  []
  (let [dir (io/file (getenv "DE_DATABASE_MIGRATIONS" "../de-database/migrations"))]
    (when-not (.isDirectory dir)
      (throw (ex-info (str "de-database migrations not found at " (.getPath dir) ". Clone "
                           "https://github.com/cyverse-de/de-database next to this repository or set "
                           "DE_DATABASE_MIGRATIONS to the path of its migrations directory.")
                      {:path (.getPath dir)})))
    dir))

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
