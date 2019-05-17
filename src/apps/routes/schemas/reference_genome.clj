(ns apps.routes.schemas.reference-genome
  (:use [common-swagger-api.schema :only [->optional-param describe]]
        [apps.routes.params]
        [schema.core :only [defschema optional-key]])
  (:require [common-swagger-api.schema.apps.reference-genomes :as schema]))

(defschema ReferenceGenomeListingParams
  (merge SecuredQueryParams
         schema/ReferenceGenomeListingParams))

(defschema ReferenceGenomeDeletionParams
  (assoc SecuredQueryParams
    (optional-key :permanent)
    (describe Boolean "If true, completely remove the reference genome from the database.")))

(defschema ReferenceGenomeSetRequest
  (-> schema/ReferenceGenome
      (->optional-param :id)))

(defschema ReferenceGenomeRequest
  (-> ReferenceGenomeSetRequest
    (->optional-param :created_by)
    (->optional-param :last_modified_by)))
