(ns jepsen.logcabin
  "Tests for LogCabin"
  (:require [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
             [core :as jepsen]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]
            [cheshire.core :as json]))

(defn install!
  "Install logcabin"
  [node]
  (info node "installing logcabin")
  (debian/install [:git-core
                   :protobuf-compiler
                   :libprotobuf-dev
                   :libcrypto++-dev
                   :g++
                   :scons])
  (c/su
    (c/cd "/"
          (when-not (cu/file? "logcabin")
            (info node "git clone logcabin")
            (c/exec :git :clone :--depth 1 "https://github.com/logcabin/logcabin.git")
            (c/cd "/logcabin"
                  (c/exec :git :submodule :update :--init))))
    (c/cd "/logcabin"
          (info node "building logcabin")
          (c/exec :scons))
    (c/exec :cp :-f "/logcabin/build/LogCabin" "/root")
    (c/exec :cp :-f "/logcabin/build/Examples/Reconfigure" "/root")
    (c/exec :cp :-f "/logcabin/build/Examples/TreeOps" "/root")
    (info node "install logcabin ok")))

(defn server-id
  [node]
  (str/replace (name node) "n" ""))

(defn server-addr
  [node]
  (str (name node) ":5254"))

(def config-file "/root/logcabin.conf")
(def log-file "/root/logcabin.log")
(def pid-file "/root/logcabin.pid")
(def store-dir "/root/storage")
(def server-addrs "n1:5254,n2:5254,n3:5254,n4:5254,n5:5254")

(defn configure!
  "Configure logcabin"
  [node]
  (info node "configuring logcabin")
  (c/su
    (c/exec :echo (str 
                    "serverId = " 
                    (server-id node) 
                    "\n" 
                    "listenAddresses = " 
                    (server-addr node))
            :> config-file)))

(defn bootstrap!
  "bootstrap logcabin"
  [node]
  (info node "bootstrapping logcabin")
  (c/su
    (c/cd "/root"
          (c/exec "/root/LogCabin" :-c config-file :-l log-file :--bootstrap))))

(defn start!
  "Start logcabin"
  [node]
  (info node "starting logcabin")
  (c/su
    (c/cd "/root"
          (c/exec "/root/LogCabin" :-c config-file :-d :-l log-file :-p pid-file))))

(defn stop!
  "Stop logcabin"
  [node]
  (info node "stopping logcabin")
  (c/su
    (cu/grepkill! :LogCabin)
    (c/exec :rm :-rf pid-file)))

(defn reconfigure!
  "Reconfigure logcabin servers"
  [node]
  (info node "reconfiguring logcabin servers")
  (c/su
    (c/cd "/root"
          (c/exec "/root/Reconfigure" 
                  :-c (c/lit server-addrs)
                  :set 
                  (c/lit (server-addr :n1))
                  (c/lit (server-addr :n2))
                  (c/lit (server-addr :n3))
                  (c/lit (server-addr :n4))
                  (c/lit (server-addr :n5))))))

(defn db
  "Sets up and tears down LogCabin"
  [version]
  (reify db/DB
    (setup! [_ test node]
            (info node "set up")
            (install! node)
            (configure! node)
            
            ; Remove log file first.
            (c/exec :rm :-rf log-file)
            
            (when (= node :n1)
              (bootstrap! node))
            
            (jepsen/synchronize test)
            (start! node)
            
            (jepsen/synchronize test)
            (when (= node :n1)
              (reconfigure! node))
            
            (jepsen/synchronize test)
            (Thread/sleep 2000))
    
    (teardown! [_ test node]
               (stop! node)
               
               (c/su 
                 (c/exec :rm :-rf store-dir))
               
               (info node "tore down"))))

(defn logcabin-set! 
  "Set a value for path"
  [node path value]
  (c/on node 
        (c/su 
          (c/cd "/root"
                (c/exec :echo :-n value | 
                        "/root/TreeOps"
                        :-c server-addrs
                        :-q
                        :-t 1
                        :write
                        (c/lit path))))))

(defn logcabin-get! 
  "get a value for path"
  [node path]
  (c/on node
        (c/su 
          (c/cd "/root"
                (c/exec "/root/TreeOps"
                        :-c server-addrs
                        :-q
                        :-t 1
                        :read
                        (c/lit path))))))


(defn logcabin-cas!
  "Set value2 for path if old value is value1"
  [node path value1 value2]
  (try 
    (c/on node
          (c/su 
            (c/cd "/root"
                  (c/exec :echo :-n value2 | 
                          "/root/TreeOps"
                          :-c server-addrs
                          :-q
                          :-p (c/lit (str path ":" value1))
                          :-t 1
                          :write
                          (c/lit path)))))
    (catch Exception e
      ; Here we treat every error as CAS false.
      false)))


(defrecord CASClient [k client]
  client/Client
  (setup! [this test node]
          (let [n node]
            (logcabin-set! node k (json/generate-string nil))
            (assoc this :client node)))
  
  (invoke! [this test op]
           (case (:f op)
             :read  (try (let [resp (logcabin-get! client k)]
                           (assoc op :type :ok :value resp))
                      (catch Exception e
                        ; Since reads don't have side effects, we can always
                        ; pretend they didn't happen.
                        (assoc op :type :fail)))
             
             :write (do (->> (:value op)
                             json/generate-string
                             (logcabin-set! client k))
                      (assoc op :type :ok))
             
             :cas   (let [[value value'] (:value op)
                          ok?            (logcabin-cas! client k
                                                        (json/generate-string value)
                                                        (json/generate-string value'))]
                      (assoc op :type (if ok? :ok :fail)))))
  
  (teardown! [_ test]))

(defn cas-client
  "A compare and set register built around a single logcabin node."
  []
  (CASClient. "/jepsen" nil))


(defn basic-test
  "A simple test for LogCabin."
  [version]
  (merge tests/noop-test
         {:os debian/os
          :db (db version)}))
