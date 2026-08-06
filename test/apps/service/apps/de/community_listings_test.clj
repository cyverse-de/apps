(ns apps.service.apps.de.community-listings-test
  (:require [apps.clients.groups :as groups]
            [apps.clients.metadata :as metadata-client]
            [apps.service.apps.de.listings :as listings]
            [apps.util.config :as config]
            [clojure.test :refer [deftest is testing]]))

(def ^:private community-attr "cyverse-community")
(def ^:private imaging-id "78f26e8c49654deb83e710aab64a25fa")
(def ^:private imaging {:id imaging-id :name "Imaging" :group_type "community"})

(deftest filter-app-ids-by-community-test
  (let [filter-by-community #'listings/filter-app-ids-by-community
        app-ids             ["app-1" "app-2"]]

    (testing "every way of naming a community filters on the community's ID"
      ;; The URL carries whatever the browser had: a community ID after the
      ;; cutover, and a full Grouper path from a bundle loaded before it. Both
      ;; have to select the same apps, or a stale tab shows an empty collection.
      (doseq [identifier [imaging-id "Imaging" "iplant:de:prod:communities:Imaging"]]
        (let [sent (atom nil)]
          (with-redefs [config/workspace-metadata-communities-attr (constantly community-attr)
                        groups/lookup-community                   (constantly imaging)
                        metadata-client/filter-by-avus            (fn [_ ids avus]
                                                                    (reset! sent avus)
                                                                    ids)]
            (is (= #{"app-1" "app-2"} (filter-by-community "someuser" identifier app-ids))
                (str "apps selected for " identifier))
            (is (= [{:attr community-attr :value imaging-id}] @sent)
                (str "filtered on the community ID for " identifier))))))

    (testing "an identifier naming no community selects nothing, and asks metadata nothing"
      ;; Filtering on an unresolvable value would match the tag rows literally,
      ;; so an orphaned tag would keep listing apps under a community that no
      ;; longer exists.
      (let [called (atom false)]
        (with-redefs [config/workspace-metadata-communities-attr (constantly community-attr)
                      groups/lookup-community                   (constantly nil)
                      metadata-client/filter-by-avus            (fn [& _] (reset! called true) app-ids)]
          (is (= #{} (filter-by-community "someuser" "iplant:de:prod:communities:Vanished" app-ids)))
          (is (false? @called)))))))
