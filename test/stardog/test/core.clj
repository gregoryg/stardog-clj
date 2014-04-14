 ; Copyright (C) 2014 Clark & Parsia
 ;
 ; Licensed under the Apache License, Version 2.0 (the "License");
 ; you may not use this file except in compliance with the License.
 ; You may obtain a copy of the License at
 ;
 ;      http://www.apache.org/licenses/LICENSE-2.0
 ;
 ; Unless required by applicable law or agreed to in writing, software
 ; distributed under the License is distributed on an "AS IS" BASIS,
 ; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ; See the License for the specific language governing permissions and
 ; limitations under the License.

(ns stardog.test.core
  (:use [stardog.core]
        [midje.sweet])
   (:import [com.complexible.stardog.api  Connection
                                          ConnectionPool
                                          ConnectionPoolConfig
                                          ConnectionConfiguration]
            [clojure.lang IFn]
            [java.util Map]
            [com.complexible.stardog.api ConnectionConfiguration Connection Query ReadQuery]
            [com.complexible.stardog.reasoning.api ReasoningType]
            [org.openrdf.query TupleQueryResult GraphQueryResult BindingSet Binding]
            [org.openrdf.model URI Literal BNode]
            [info.aduna.iteration Iteration]))


(def test-db-spec (create-db-spec "testdb" "snarl://localhost:5820/" "admin" "admin" "none"))
(def reasoning-db-spec (create-db-spec "testdb" "snarl://localhost:5820/" "admin" "admin" "QL"))
(def test-connection (connect test-db-spec))

(facts "About stardog connection pool handling"
       (fact "create-db-spec returns a valid map"
             (create-db-spec "testdb" "snarl://localhost:5820/" "admin" "admin" "none") =>
                             {:url "snarl://localhost:5820/" :db "testdb" :pass "admin" :user "admin" :max-idle 100 :max-pool 200 :min-pool 10 :reasoning "none"})
       (fact "make-datasource creates a map with a connection pool"
             (str (type (:ds (make-datasource (create-db-spec "testdb" "snarl://localhost:5820/" "admin" "admin" "none"))))) =>
             (contains "com.complexible.stardog.api.ConnectionPool")))

(facts "About stardog connection handling"
       (fact "create a stardog connection"
             (.isOpen (connect test-db-spec)) => truthy)
       (fact "close a stardog connection"
             (let [c (connect test-db-spec)]
                   (.close c)
                   (.isOpen c)) => falsey))

(facts "About Stardog SPARQL queries"
       (with-open [c (connect test-db-spec)]
            (let [r (query c "select ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5")]
         (fact "query results should have 5 elements"
               (count r) => 5))))

(facts "About add and remove triples"
       (fact "Insert a vector representing a triple"
             (with-open [c (connect test-db-spec)]
               (with-transaction [c] (insert! c ["urn:test" "urn:test:clj:prop" "Hello World"]))) => nil)
        (fact "Insert a vector representing a triple"
             (with-open [c (connect test-db-spec)]
               (with-transaction [c] (remove! c ["urn:test" "urn:test:clj:prop" "Hello World"]))) => nil)
        (fact "Attempting to insert a partial statement throws IllegalArgumentException"
               (with-open [c (connect test-db-spec)]
               (with-transaction [c] (insert! c ["urn:test" "urn:test:clj:prop"]))) => (throws IllegalArgumentException))
        (fact "Multiple inserts in a tx"
               (with-open [c (connect test-db-spec)]
               (with-transaction [c]
                 (insert! c ["urn:test" "urn:test:clj:prop2" "Hello World"])
                 (insert! c ["urn:test" "urn:test:clj:prop2" "Hello World2"]))
               (count (query c "select ?s ?p ?o WHERE { ?s <urn:test:clj:prop2> ?o } LIMIT 5")) => 2) )
       (fact "Multiple inserts in a tx"
               (with-open [c (connect test-db-spec)]
               (with-transaction [c]
                 (remove! c ["urn:test" "urn:test:clj:prop2" "Hello World"])
                 (remove! c ["urn:test" "urn:test:clj:prop2" "Hello World2"]))
               (count (query c "select ?s ?p ?o WHERE { ?s <urn:test:clj:prop2> ?o } LIMIT 5")) => 0) ))


(facts "About query converter handling"
       (fact "Convert keys to strings"
             (with-open [c (connect test-db-spec)]
                 (let [r (query c "select ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5" {:key-converter #(.toString %)})]
               (keys (first r))) => ["s" "p" "o"]))
       (fact "Convert values to strings"
             (with-open [c (connect test-db-spec)]
                  (let [r (query c "select ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5" {:converter #(str "aaa" %)})]
               (first (vals (first r)))) => (contains "aaa"))))

(facts "About reasoning connections"
       (fact "use reasoning on a connect"
            (with-open [c (connect reasoning-db-spec)]
             (let [r (query c "select ?s ?p ?o WHERE { ?s a <http://www.lehigh.edu/~zhp2/2004/0401/univ-bench.owl#Person> }")]
               (count r) => 719)))
       (fact "use reasoning on a connection pool"
              (let [ds (make-datasource reasoning-db-spec)]
                  (with-connection-pool [c ds]
                   (let [r (query c "select ?s ?p ?o WHERE { ?s a <http://www.lehigh.edu/~zhp2/2004/0401/univ-bench.owl#Person> }")]
                     (count r) => 719)))))


(facts "About query limit handling"
       (fact "Handle query limit"
             (with-open [c (connect test-db-spec)]
               (let [r (query c "select ?s ?p ?o WHERE { ?s ?p ?o }" {:limit 5})]
               (count r)) => 5)))

(facts "About ask queries"
       (fact "ask queries can use connections"
             (with-open [c (connect test-db-spec)]
               (ask c "ask { ?s <http://www.lehigh.edu/~zhp2/2004/0401/univ-bench.owl#teacherOf> ?o }")) => truthy))

(facts "About update queries"
       (fact "update queriers can use connections"
             (with-open [c (connect test-db-spec)]
               (with-transaction [c]
                 (insert! c ["urn:testUpdate:a1" "urn:testUpdate:b" "aloha world"]))
               (update c "DELETE { ?a ?b \"aloha world\" } INSERT { ?a ?b \"shalom world\" } WHERE { ?a ?b \"aloha world\"  }"
                         {:parameters {"?a" "urn:testUpdate:a1" "?b" "urn:testUpdate:b"}})
               (ask c "ask { ?s ?p \"shalom world\" }") => truthy)))

(facts "About transact with pools"
        (fact "use transact with a connection pool"
              (let [ds (make-datasource reasoning-db-spec)]
                  (transact ds
                     (fn [conn]
                        (insert! conn ["urn:test" "urn:test:clj:prop3" "Hello World"])))
                  (with-connection-pool [c ds]
                   (count (query c "select ?s ?p ?o WHERE { ?s <urn:test:clj:prop3> ?o } LIMIT 5")) => 1) )))



