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
            [jepsen.os.debian :as debian]))

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
    (c/exec "/root/LogCabin" :-c config-file :--bootstrap)))

(defn start!
  "Start logcabin"
  [node]
  (info node "starting logcabin")
  (c/su
    (c/exec "/root/LogCabin" :-c config-file :-d :-l log-file :-p pid-file)))

(defn stop!
  "Stop logcabin"
  [node]
  (info node "stopping logcabin")
  (c/su
    (cu/grepkill! :LogCabin)
    (c/exec :rm :-rf pid-file)))

(defn db
  "Sets up and tears down LogCabin"
  [version]
  (reify db/DB
    (setup! [_ test node]
            (info node "set up")
            (install! node)
            (configure! node)
            (when (= node :n1)
              (bootstrap! node))
            
            (jepsen/synchronize test)
            (start! node))
    
    (teardown! [_ test node]
               (stop! node)
               
               (c/su 
                 (c/exec :rm :-rf store-dir)
                 (c/exec :rm :-rf log-file))
               
               (info node "tore down"))))

(defn basic-test
  "A simple test for LogCabin."
  [version]
  (merge tests/noop-test
         {:os debian/os
          :db (db version)}))
