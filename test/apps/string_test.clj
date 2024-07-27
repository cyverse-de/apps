(ns apps.string-test
  (:require [apps.util.string :refer [render]]
            [clojure.test :refer [deftest is testing]]))

(deftest test-render
  (let [cases [{:desc     "base case"
                :fmt      "This is a {{foo}} of the {{bar}}."
                :vals     {:foo "test" :bar "Emergency Broadcast System"}
                :expected "This is a test of the Emergency Broadcast System."}
               {:desc     "empty format string"
                :fmt      ""
                :vals     {:another "test"}
                :expected ""}
               {:desc     "missing key"
                :fmt      "This is a {{foo}} of the {{bar}}."
                :vals     {:foo "test"}
                :expected "This is a test of the {{bar}}."}]]
    (doseq [{:keys [desc fmt vals expected]} cases]
      (testing desc
        (is (= expected (render fmt vals)))))))
