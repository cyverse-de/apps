(ns apps.service.apps.communities-test
  (:require [apps.clients.groups :as groups]
            [apps.service.apps.communities :as communities]
            [apps.util.config :as config]
            [clojure.test :refer [deftest is testing]]))

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
