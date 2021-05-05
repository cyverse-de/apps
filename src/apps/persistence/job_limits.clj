(ns apps.persistence.job-limits
  "This namespace contains functions used to check job limits in the DE database. Technically speaking, these functions
   could be placed in `apps.persistence.jobs`, but we're moving from Korma to HoneySQL, and using a new namespace for
   the new method of accessing the database seemed prudent."
  (:require [apps.constants :as c]
            [apps.persistence.jobs :as jp]
            [apps.util.db :as db]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as sql]
            [honeysql.helpers :as h]))

(defn- count-concurrent-vice-jobs-query
  "Builds a query to list the concurrently running VICE jobs for a user."
  [username]
  (-> (h/select (sql/call :count (sql/raw "DISTINCT j.id")))
      (h/from [:jobs :j])
      (h/join [:users :u] [:= :j.user_id :u.id]
              [:job_steps :s] [:= :j.id :s.job_id]
              [:job_types :jt] [:= :s.job_type_id :jt.id])
      (h/where [:not-in :j.status jp/completed-status-codes]
               [:= :jt.system_id c/interactive-system-id]
               [:= :u.username username])
      sql/format))

(defn count-concurrent-vice-jobs
  "Counts the number of currently running VICE jobs for a user."
  [username]
  (db/with-transaction [tx]
    (-> (jdbc/query tx (count-concurrent-vice-jobs-query username))
        first
        :count)))
