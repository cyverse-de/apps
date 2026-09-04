(ns apps.service.util-test
  (:require
   [apps.service.util :as util]
   [clojure.test :refer [deftest is testing]]
   [slingshot.test]))

;; ---------------------------------------------------------------------------
;; avu-filter-specified? tests
;; ---------------------------------------------------------------------------

(deftest avu-filter-specified-with-both-params
  (testing "The AVU filter is specified when both the attribute and the value are present"
    (is (util/avu-filter-specified? {:attribute "attr" :attribute_value "value"}))))

(deftest avu-filter-not-specified-with-neither-param
  (testing "The AVU filter is not specified when neither the attribute nor the value is present"
    (is (not (util/avu-filter-specified? {})))
    (is (not (util/avu-filter-specified? {:attribute "" :attribute_value ""})))))

(deftest avu-filter-not-specified-with-one-param
  (testing "The AVU filter is not specified when only one of the two parameters is present"
    (is (not (util/avu-filter-specified? {:attribute "attr"})))
    (is (not (util/avu-filter-specified? {:attribute_value "value"})))))

;; ---------------------------------------------------------------------------
;; validate-avu-filter-params tests
;; ---------------------------------------------------------------------------

(deftest validate-avu-filter-params-accepts-both-or-neither
  (testing "Specifying both AVU filtering parameters or neither of them is valid"
    (is (nil? (util/validate-avu-filter-params {})))
    (is (nil? (util/validate-avu-filter-params {:attribute "attr" :attribute_value "value"})))))

(deftest validate-avu-filter-params-rejects-one-param
  (testing "Specifying just one of the AVU filtering parameters is a bad request"
    (is (thrown+? [:type :clojure-commons.exception/bad-request]
                  (util/validate-avu-filter-params {:attribute "attr"})))
    (is (thrown+? [:type :clojure-commons.exception/bad-request]
                  (util/validate-avu-filter-params {:attribute_value "value"})))))
