(ns apps.routes.schemas.analysis
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [schema.core :only [defschema]])
  (:require [common-swagger-api.schema.analyses :as schema]))

(defschema StopAnalysisRequest
  (merge SecuredQueryParams schema/StopAnalysisRequest))
