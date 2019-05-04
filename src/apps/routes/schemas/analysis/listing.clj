(ns apps.routes.schemas.analysis.listing
  (:use [common-swagger-api.schema :only [describe]]
        [schema.core :only [defschema]])
  (:require [common-swagger-api.schema.analyses.listing :as schema]))

(defschema ExternalIdList
  {:external_ids (describe [schema/ExternalId] "The list of external identifiers.")})

(defschema AdminAnalysis
  (assoc schema/BaseAnalysis
    :external_ids (describe [schema/ExternalId] "The list of external identifiers.")))

(defschema AdminAnalysisList
  {:analyses (describe [AdminAnalysis] "The list of anlayses.")})
