(ns ^:endpoint apps.endpoints.apps-test
  "Endpoint tests for the app lifecycle: create, view, search, share, update, and delete. These go through the real
   routes, middleware, and database, with the other DE services replaced by the fakes in apps.harness.fakes."
  (:require
   [apps.harness.fakes :as fakes]
   [apps.harness.golden :as golden]
   [apps.harness.system :as sys :refer [call new-user unique-name]]
   [apps.service.apps.test-fixtures :as atf]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once sys/with-system)
(use-fixtures :each sys/fail-on-unhandled-requests)

(defn- create-app [user name]
  (let [response (call user :post "/apps/de" {:body (assoc atf/app-definition :name name)})]
    (is (= 200 (:status response)) (pr-str (:body response)))
    (:body response)))

(defn- search [user term]
  (call user :get "/apps" {:query {:search term}}))

(defn- found-names [response]
  (set (map :name (get-in response [:body :apps]))))

(defn- share-app [owner grantee app-id level]
  (call owner :post "/apps/sharing"
        {:body {:sharing [{:subject {:id (:user grantee) :source_id "ldap"}
                           :apps    [{:system_id "de" :app_id app-id :permission level}]}]}}))

(defn- relabel [user app new-name]
  (call user :patch (str "/apps/de/" (:id app)) {:body (-> app (dissoc :versions) (assoc :name new-name))}))

(deftest create-app-and-read-it-back
  (let [user   (new-user)
        name   (unique-name "lifecycle app")
        app    (create-app user name)
        app-id (:id app)]

    (testing "the created app echoes the definition"
      (is (= name (:name app)))
      (is (= ["Parameters"] (map :label (:groups app))))
      (is (= ["wc"] (map :name (:tools app)))))

    (testing "the job view describes the app as it will be launched"
      (let [{:keys [status body]} (call user :get (str "/apps/de/" app-id))]
        (is (= 200 status))
        (is (= "executable" (:overall_job_type body)))
        (is (= ["Input file" "Output file name"] (map :label (mapcat :parameters (:groups body)))))
        (is (= {:canRun true :results []} (:limitChecks body)))))

    (testing "the details reflect ownership and integrator information"
      (let [{:keys [status body]} (call user :get (str "/apps/de/" app-id "/details"))]
        (is (= 200 status))
        (is (= "own" (:permission body)))
        (is (= false (:is_public body)))
        (is (= (:email user) (:integrator_email body)))))

    (testing "the app shows up in the owner's search results"
      (is (= #{name} (found-names (search user name)))))))

(deftest app-details-match-golden-file
  (let [user (new-user)
        app  (create-app user (unique-name "golden app"))
        {:keys [status body]} (call user :get (str "/apps/de/" (:id app) "/details"))]
    (is (= 200 status))
    (is (apply = (golden/check "apps/app-details" body)))))

(deftest sharing-controls-visibility
  (let [owner  (new-user)
        other  (new-user)
        name   (unique-name "shared app")
        app    (create-app owner name)
        app-id (:id app)]

    (testing "other users can't see a private app"
      (is (empty? (found-names (search other name))))
      (is (= 403 (:status (call other :get (str "/apps/de/" app-id "/details"))))))

    (testing "sharing grants the requested access"
      (let [{:keys [status body]} (share-app owner other app-id "read")]
        (is (= 200 status))
        (is (every? :success (mapcat :apps (:sharing body)))))
      (is (some #(re-find #"has been shared with" (:subject %)) (fakes/notifications-for (:user owner))))
      (is (= #{name} (found-names (search other name))))
      (is (= "read" (get-in (call other :get (str "/apps/de/" app-id "/details")) [:body :permission]))))

    (testing "read access doesn't allow editing"
      (is (= 403 (:status (relabel other app "hijacked")))))

    (testing "unsharing revokes access"
      (let [{:keys [status]} (call owner :post "/apps/unsharing"
                                   {:body {:unsharing [{:subject {:id (:user other) :source_id "ldap"}
                                                        :apps    [{:system_id "de" :app_id app-id}]}]}})]
        (is (= 200 status)))
      (is (empty? (found-names (search other name)))))))

(deftest updating-an-app
  (let [user     (new-user)
        app      (create-app user (unique-name "app to update"))
        app-id   (:id app)
        new-name (unique-name "renamed app")]

    (testing "relabeling changes the name"
      (let [{:keys [status body]} (relabel user app new-name)]
        (is (= 200 status))
        (is (= new-name (:name body)))))

    (testing "a full update replaces the parameters"
      (let [definition (-> (assoc atf/app-definition :name new-name :description "Updated")
                           (update-in [:groups 0 :parameters] (comp vec butlast)))
            {:keys [status body]} (call user :put (str "/apps/de/" app-id) {:body definition})]
        (is (= 200 status) (pr-str body))
        (is (= "Updated" (:description body)))
        (is (= ["Input file"] (map :label (mapcat :parameters (:groups body)))))))

    (testing "searches see the new name"
      (is (= #{new-name} (found-names (search user new-name)))))))

(deftest deleting-an-app
  (let [user   (new-user)
        name   (unique-name "app to delete")
        app-id (:id (create-app user name))]
    (is (= 200 (:status (call user :delete (str "/apps/de/" app-id)))))
    (testing "a deleted app is marked as such and drops out of searches"
      (is (= true (get-in (call user :get (str "/apps/de/" app-id "/details")) [:body :deleted])))
      (is (empty? (found-names (search user name)))))))

(deftest missing-and-malformed-app-ids
  (let [user (new-user)]
    (is (= 404 (:status (call user :get (str "/apps/de/" (random-uuid) "/details")))))
    (is (= 400 (:status (call user :get "/apps/de/not-a-uuid/details"))))))
