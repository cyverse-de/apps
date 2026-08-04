(ns apps.routes.schemas.groups
  (:require
   [common-swagger-api.schema :refer [describe NonBlankString]]
   [schema.core :as s]))

(s/defschema Group
  {:id
   (describe String "The group ID.")

   :name
   (describe String "The group's short name.")

   :group_type
   (describe String "The kind of group.")

   (s/optional-key :display_name)
   (describe String "The displayable group name.")

   (s/optional-key :description)
   (describe String "A brief description of the group.")})

(s/defschema Subject
  {:id
   (describe String "The subject ID.")

   (s/optional-key :name)
   (describe String "The subject name.")

   (s/optional-key :first_name)
   (describe String "The subject's first name.")

   (s/optional-key :last_name)
   (describe String "The subject's last name.")

   (s/optional-key :email)
   (describe String "The subject email.")

   (s/optional-key :institution)
   (describe String "The subject institution.")

   (s/optional-key :attribute_values)
   (describe [String] "A list of additional attributes applied to the subject.")

   (s/optional-key :description)
   (describe String "The subject description")

   :source_id
   (describe String "The ID of the source of the subject information.")})

(s/defschema GroupMembers
  {:members (describe [Subject] "The list of group members.")})

(s/defschema GroupMembersUpdate
  {:members (describe [NonBlankString] "The new list of member subject IDs.")})

(s/defschema GroupMembersUpdateResponse
  (assoc GroupMembers
         :failures (describe [String] "The list of subject IDs that could not be added to the group.")))
