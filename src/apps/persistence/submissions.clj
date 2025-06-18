(ns apps.persistence.submissions
  (:require [cheshire.core :as json]
            [kameleon.uuids :refer [uuidify]]
            [korma.core :as sql]))

(defn get-submission-by-id [submission-id]
  (some-> (sql/select* :submissions)
          (sql/fields :submission)
          (sql/where {:id (uuidify submission-id)})
          sql/select
          first
          :submission
          .getValue
          (json/decode true)))
