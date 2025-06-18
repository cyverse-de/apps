(ns apps.routes.schemas.analysis.listing
  (:require
   [common-swagger-api.schema :refer [describe]]
   [common-swagger-api.schema.analyses.listing :as schema]
   [schema.core :refer [defschema]]))

(defschema ExternalIdList
  {:external_ids (describe [schema/ExternalId] "The list of external identifiers.")})

(defschema AdminAnalysis
  (assoc schema/BaseAnalysis
         :external_ids (describe [schema/ExternalId] "The list of external identifiers.")))

(defschema AdminAnalysisList
  {:analyses (describe [AdminAnalysis] "The list of anlayses.")})
