(ns apps.persistence.submissions
  (:use [kameleon.uuids :only [uuidify]]
        [korma.core :exclude [update]])
  (:require [cheshire.core :as json]))

(defn get-submission-by-id [submission-id]
  (some-> (select* :submissions)
          (fields :submission)
          (where {:id (uuidify submission-id)})
          select
          first
          :submission
          .getValue
          (json/decode true)))
