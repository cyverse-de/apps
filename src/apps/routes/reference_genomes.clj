(ns apps.routes.reference-genomes
  (:require
   [apps.metadata.reference-genomes :refer [get-reference-genome list-reference-genomes]]
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.routes.schemas.reference-genome :refer [ReferenceGenomeListingParams]]
   [common-swagger-api.schema :refer [defroutes GET]]
   [common-swagger-api.schema.apps.reference-genomes :as schema]
   [ring.util.http-response :refer [ok]]))

(defroutes reference-genomes
  (GET "/" []
    :query [params ReferenceGenomeListingParams]
    :return schema/ReferenceGenomesList
    :summary schema/ReferenceGenomeListingSummary
    :description schema/ReferenceGenomeListingDocs
    (ok (list-reference-genomes params)))

  (GET "/:reference-genome-id" []
    :path-params [reference-genome-id :- schema/ReferenceGenomeIdParam]
    :query [params SecuredQueryParams]
    :return schema/ReferenceGenome
    :summary schema/ReferenceGenomeDetailsSummary
    :description schema/ReferenceGenomeDetailsDocs
    (ok (get-reference-genome reference-genome-id))))
