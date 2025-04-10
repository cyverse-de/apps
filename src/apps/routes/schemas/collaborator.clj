(ns apps.routes.schemas.collaborator
  (:require [common-swagger-api.schema :refer [describe]]
            [schema.core :refer [defschema optional-key]]))

(defschema Collaborator
  {(optional-key :id)          (describe String "The collaborator's ID in Trellis")
   (optional-key :email)       (describe String "The collaborator's email address")
   (optional-key :firstname)   (describe String "The collaborator's first name")
   (optional-key :lastname)    (describe String "The collaborator's last name")
   :username                   (describe String "The collaborator's iPlant username")
   (optional-key :position)    (describe String "The collaborator's job title")
   (optional-key :institution) (describe String "The collaborator's institutional affiliation")})

(defschema Collaborators
  {:users (describe [Collaborator] "The list of collaborators.")})
