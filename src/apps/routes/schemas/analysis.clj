(ns apps.routes.schemas.analysis
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [common-swagger-api.schema.analyses :as schema]
   [schema.core :refer [defschema]]))

(defschema StopAnalysisRequest
  (merge SecuredQueryParams schema/StopAnalysisRequest))
