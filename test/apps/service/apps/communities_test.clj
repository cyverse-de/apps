(ns apps.service.apps.communities-test
  (:require [apps.clients.groups :as groups]
            [apps.service.apps.communities :as communities]
            [apps.util.config :as config]
            [clojure.test :refer [deftest is testing]]
            [slingshot.slingshot :refer [try+]]))

(def ^:private community-attr "cyverse-community")
(def ^:private imaging-id "78f26e8c49654deb83e710aab64a25fa")

(defn- avu [value]
  {:attr community-attr :value value :unit ""})

(defmacro with-community-attr [& body]
  `(with-redefs [config/workspace-metadata-communities-attr (constantly community-attr)]
     ~@body))

(deftest request-community-identifiers-test
  (testing "the identifiers a request names"
    (let [identifiers #'communities/request-community-identifiers]
      (with-community-attr
        (doseq [[description request expected]
                [["the current form" {:community_ids [imaging-id]} [imaging-id]]
                 ["several communities" {:community_ids ["a" "b"]} ["a" "b"]]
                 ;; What a browser holding a bundle from before the cutover sends.
                 ["the legacy AVU form" {:avus [(avu "iplant:de:de:communities:Imaging")]}
                  ["iplant:de:de:communities:Imaging"]]
                 ["duplicate AVUs collapse" {:avus [(avu "Imaging") (avu "Imaging")]} ["Imaging"]]
                 ;; community_ids wins so a client sending both is not ambiguous.
                 ["both forms present" {:community_ids [imaging-id] :avus [(avu "Imaging")]} [imaging-id]]
                 ["AVUs for another attribute" {:avus [{:attr "other" :value "x" :unit ""}]} nil]
                 ["an empty community list" {:community_ids []} nil]
                 ["nothing at all" {} nil]]]
          (is (= expected (identifiers request)) description))))))

(deftest community-avus-test
  (testing "the stored tag is the community ID, never its name"
    (let [community-avus #'communities/community-avus]
      (with-community-attr
        (is (= [{:attr community-attr :value imaging-id :unit ""}]
               (community-avus [{:id imaging-id :name "Imaging" :group_type "community"}])))))))

(defmacro ^:private caught-type
  "Evaluates `body`, returning the slingshot `:type` it throws, or ::none."
  [& body]
  `(try+
    ~@body
    ::none
    (catch map? e# (:type e#))))

(deftest lookup-community-identifier-forms-test
  (let [resolve-community #'communities/resolve-community
        imaging           {:id imaging-id :name "Imaging" :group_type "community"}]
    (with-redefs-fn {#'groups/lookup-group    (fn [_group-type name] (when (= "Imaging" name) imaging))
                     #'groups/get-group-by-id (constantly nil)}
      (fn []
        (testing "a legacy Grouper communities path resolves by its short name"
          (is (= imaging (resolve-community "iplant:de:de:communities:Imaging"))))
        (testing "a plain name resolves"
          (is (= imaging (resolve-community "Imaging"))))
        (testing "a colon path outside communities must not capture a community by its last segment"
          (is (= :clojure-commons.exception/not-found
                 (caught-type (resolve-community "iplant:de:prod:teams:Imaging")))))))))

(deftest lookup-community-branch-precedence-test
  (testing "which lookup each identifier form goes through"
    (let [imaging (fn [via] {:id imaging-id :name "Imaging" :group_type "community" :via via})]
      (with-redefs-fn {#'groups/get-group-by-id (fn [group-id] (imaging [:id group-id]))
                       #'groups/lookup-group    (fn [_group-type name] (imaging [:name name]))}
        (fn []
          (is (= [:id imaging-id] (:via (groups/lookup-community imaging-id)))
              "a 32-hex identifier is looked up as a group ID")
          (is (= [:name "Imaging"] (:via (groups/lookup-community "Imaging")))
              "a plain name is looked up as a community name")
          (is (= [:name "Imaging"] (:via (groups/lookup-community "iplant:de:de:communities:Imaging")))
              "a communities path is looked up by its short name"))))))

(deftest resolve-community-test
  (let [resolve-community #'communities/resolve-community
        imaging           {:id imaging-id :name "Imaging" :group_type "community"}]
    (testing "an identifier that names a community resolves to it"
      (with-redefs [groups/lookup-community (constantly imaging)]
        (is (= imaging (resolve-community "Imaging")))))

    (testing "an identifier that names nothing is a 404, not a silently stored tag"
      (with-redefs [groups/lookup-community (constantly nil)]
        (is (thrown? Exception (resolve-community "iplant:de:de:communities:Gone")))))))

(deftest admin-still-resolves-communities-test
  (testing "an administrator skips the community-admin check but not resolution"
    ;; Skipping resolution for administrators is what allowed an unresolvable
    ;; value to be written verbatim, producing a tag no listing can match.
    (let [resolve-request #'communities/resolve-request-communities]
      (with-community-attr
        (with-redefs [groups/lookup-community (constantly nil)]
          (is (thrown? Exception
                       (resolve-request "someadmin" {:community_ids ["nope"]} true))))))))
