(ns apps.service.apps.de.jobs.io-tickets
  (:require [apps.clients.data-info :as data-info]
            [apps.service.apps.jobs.util :as job-util]))

(def ^:private extract-input-paths
  "This is a transform used to extract input paths from a list of job steps."
  (comp (map :config)
        (mapcat :input)
        (map :value)
        (remove empty?)))

(defn- get-input-tickets
  "Creates iRODS tickets for a set of input paths."
  [user paths]
  (if-not (empty? paths)
    (:tickets (data-info/create-tickets user
                                        paths
                                        :mode       "read"
                                        :public     false
                                        :uses-limit 0))
    []))

(defn- get-output-ticket
  "Creates an iRODS ticket for the output directory of a submission."
  [user path]
  (->> (data-info/create-tickets user
                                 [path]
                                 :mode             "write"
                                 :public           false
                                 :uses-limit       0
                                 :file-write-limit 0)
       :tickets first))

(defn- add-tickets-to-inputs
  "Adds tickets to a list of inputs."
  [inputs ticket-map]
  (mapv (fn [{:keys [value] :as input}]
          (if (contains? ticket-map value)
            (assoc input :ticket (ticket-map value))
            input))
        inputs))

(defn- add-tickets-to-steps
  "Adds tickets to a list of steps."
  [steps ticket-map]
  (mapv #(update-in % [:config :input] add-tickets-to-inputs ticket-map) steps))

(defn add-tickets
  "Adds I/O tickets to a job submission. The output ticket will be added to the submission itself. The input tickets
   will be added with each input parameter, and all of the tickets will be added to a map from iRODS path to ticket
   string at the top level of the submission. This is done as a post-processing step after building the submission.
   On one hand, doing this as a post-processing step seems kind of clunky. On the other hand, doing it after the
   submission has been mostly built allows us to consolidate the ticket generation for all steps and makes it easier
   to keep a copy of the ticket information in an easily accessible place in the job submission."
  [user {:keys [steps] :as submission}]
  (let [output-path   (job-util/create-output-dir user submission)
        input-paths   (remove (partial = output-path)
                              (into [] extract-input-paths (:steps submission)))
        input-tickets (get-input-tickets user input-paths)
        output-ticket (get-output-ticket user output-path)
        ticket-map    (into {} (map (juxt :path :ticket-id) (conj input-tickets output-ticket)))]
    (assoc (update submission :steps add-tickets-to-steps ticket-map)
      :output_dir        output-path
      :output_dir_ticket (:ticket-id output-ticket)
      :ticket_map        ticket-map)))

(defn delete-tickets
  "Deletes the tickets in a map from iRODS path to ticket string."
  [user ticket-map]
  (data-info/delete-tickets user (vals ticket-map)))
