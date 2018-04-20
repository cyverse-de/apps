(ns apps.service.apps.de.jobs.io-tickets
  (:require [apps.clients.data-info :as data-info]
            [apps.service.apps.jobs.util :as job-util]))

(defn update-with-download-tickets
  "Takes a list if input params,
   creates a download ticket for the path of each input's :value,
   and updates those inputs with the created ticket in a :ticket field.
   For use with apps.service.apps.de.jobs.protocol/buildInputs"
  [user params]
  (if (or (empty? params) (every? (comp empty? :value) params))
    params
    (let [paths      (remove empty? (map :value params))
          ticket-map (->> (data-info/create-tickets user
                                                    paths
                                                    :mode       "read"
                                                    :public     false
                                                    :uses-limit 0)
                          :tickets
                          (map (juxt :path :ticket-id))
                          (into {}))]
      (map #(assoc % :ticket (ticket-map (:value %)))
           params))))

(defn update-with-output-dir-ticket
  "Returns the given submission updated with an :output_dir_ticket field,
   containting an upload ticket for :output_dir (which will also be created if necessary).
   For use with apps.service.apps.de.jobs.protocol/buildSubmission"
  [user submission]
  (let [output-dir (job-util/create-output-dir user submission)
        ticket     (->> (data-info/create-tickets user
                                                  [output-dir]
                                                  :mode             "write"
                                                  :public           false
                                                  :uses-limit       0
                                                  :file-write-limit 0)
                        :tickets
                        first
                        :ticket-id)]
    (assoc submission
           :output_dir        output-dir
           :output_dir_ticket ticket)))
