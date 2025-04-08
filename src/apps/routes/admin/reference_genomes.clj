(ns apps.routes.admin.reference-genomes
  (:require [apps.metadata.reference-genomes
             :refer [add-reference-genome
                     delete-reference-genome
                     update-reference-genome]]
            [apps.routes.params :refer [SecuredQueryParams]]
            [apps.routes.schemas.reference-genome :refer [ReferenceGenomeDeletionParams]]
            [common-swagger-api.schema
             :refer [defroutes
                     DELETE
                     PATCH
                     POST]]
            [common-swagger-api.schema.apps.reference-genomes
             :refer [ReferenceGenome
                     ReferenceGenomeIdParam]]
            [common-swagger-api.schema.apps.admin.reference-genomes :as schema]
            [ring.util.http-response :refer [ok]]))

(defroutes reference-genomes
  (POST "/" []
    :query [params SecuredQueryParams]
    :body [body schema/ReferenceGenomeAddRequest]
    :return ReferenceGenome
    :summary schema/ReferenceGenomeAddSummary
    :description schema/ReferenceGenomeAddDocs
    (ok (add-reference-genome body)))

  (DELETE "/:reference-genome-id" []
    :path-params [reference-genome-id :- ReferenceGenomeIdParam]
    :query [params ReferenceGenomeDeletionParams]
    :summary schema/ReferenceGenomeDeleteSummary
    :description schema/ReferenceGenomeDeleteDocs
    (ok (delete-reference-genome reference-genome-id params)))

  (PATCH "/:reference-genome-id" []
    :path-params [reference-genome-id :- ReferenceGenomeIdParam]
    :query [params SecuredQueryParams]
    :body [body schema/ReferenceGenomeUpdateRequest]
    :return ReferenceGenome
    :summary schema/ReferenceGenomeUpdateSummary
    :description schema/ReferenceGenomeUpdateDocs
    (ok (update-reference-genome (assoc body :id reference-genome-id)))))
