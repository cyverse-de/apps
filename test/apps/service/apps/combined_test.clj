(ns apps.service.apps.combined-test
  (:use [clojure.test]
        [kameleon.uuids :only [uuid]]
        [apps.service.apps.combined]))

(def client1-apps-listing {:total 5, :apps [{:id (uuid)} {:id (uuid)} {:id (uuid)} {:id (uuid)} {:id (uuid)}]})
(def client2-apps-listing nil)
(def client3-apps-listing {:total 3, :apps [{:id "a"} {:id "b"} {:id "c"}]})
(def combined-app-set (set (concat (:apps client1-apps-listing) (:apps client3-apps-listing))))

(deftype TestClient1 []
  apps.protocols.Apps

  (listAppsUnderHierarchy
    [_ root-iri attr params]
    client1-apps-listing)

  (adminListAppsUnderHierarchy
    [_ ontology-version root-iri attr params]
    client1-apps-listing))

(deftype TestClient2 []
  apps.protocols.Apps

  (listAppsUnderHierarchy
    [_ root-iri attr params]
    client2-apps-listing)

  (adminListAppsUnderHierarchy
    [_ ontology-version root-iri attr params]
    client2-apps-listing))

(deftype TestClient3 []
  apps.protocols.Apps

  (listAppsUnderHierarchy
    [_ root-iri attr params]
    client3-apps-listing)

  (adminListAppsUnderHierarchy
    [_ ontology-version root-iri attr params]
    client3-apps-listing))

(def combined-client (apps.service.apps.combined.CombinedApps.
                      [(TestClient1.) (TestClient2.) (TestClient3.)]
                      "someuser"))

(deftest CombinedApps-listAppsUnderHierarchy-test
  (let [{:keys [total apps]} (.listAppsUnderHierarchy combined-client
                                                      "root-iri"
                                                      "attr"
                                                      {:sort-field "id"})]
    (testing "Test CombinedApps.listAppsWithMetadata app count and apps list."
      (is (= total 8))
      (is (= (set apps) combined-app-set)))))

(deftest CombinedApps-adminListAppsUnderHierarchy-test
  (let [{:keys [total apps]} (.adminListAppsUnderHierarchy combined-client
                                                           "ontology-version"
                                                           "root-iri"
                                                           "attr"
                                                           {:sort-field "id"})]
    (testing "Test CombinedApps.adminListAppsUnderHierarchy app count and apps list."
      (is (= total 8))
      (is (= (set apps) combined-app-set)))))
