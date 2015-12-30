(ns jepsen.logcabin-test
  (:require [clojure.test :refer :all]
            [jepsen.core :refer [run!]]
            [jepsen.logcabin :as logcabin]))

(def version
  "What LogCabin version should we test?"
  "HEAD")

(deftest baisc-test
  (is (:valid? (:results (run! (logcabin/basic-test version))))))
