(ns couchdb.client.tests
  (:import (java.io FileInputStream))
  (:require (couchdb [client :as couchdb])
            (clojure.contrib [error-kit :as kit]))
  (:use (clojure test)
        [clojure.contrib.duck-streams :only [reader]]
        [clojure.contrib.java-utils :only [file]]))


(def +test-server+ "http://localhost:5984/")
(def +test-db+ "clojure-couchdb-test-database")
(def +test-db2+  "clojure-couchdb-test-database2")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;         Utilities           ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- qualify-sym [sym]
  (let [v (resolve sym)]
    (assert v)
    (apply symbol (map #(str (% (meta v))) [:ns :name]))))

(defmethod assert-expr 'raised? [msg [_ error-type & body :as form]]
  (let [error-name (qualify-sym error-type)]
    `(kit/with-handler
      (do
        ~@body
        (report {:type :fail
                 :message ~msg
                 :expected '~form
                 :actual ~(str error-name " not raised.")}))
      (kit/handle ~error-type {:as err#}
              (report {:type :pass
                       :message ~msg
                       :expected '~form
                       :actual nil})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;           Tests             ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest databases
  ;; get a list of existing DBs
  (let [db-list (couchdb/database-list +test-server+)
        has-test-db? (some #{+test-db+} db-list)]
    ;; if the db exists, delete it
    (when has-test-db?
      (is (= (couchdb/database-delete +test-server+ +test-db+) true))
      (is (= (- (count db-list) 1)
             (count (couchdb/database-list +test-server+)))))
    ;; now create the db
    (is (= (couchdb/database-create +test-server+ +test-db+) +test-db+))
    (if has-test-db?
      (is (= (count db-list)
             (count (couchdb/database-list +test-server+))))
      (is (= (+ (count db-list) 1)
             (count (couchdb/database-list +test-server+))))))
  ;; now get info about the db
  (let [info (couchdb/database-info +test-server+ +test-db+)]
    (is (= (:db_name info) +test-db+))
    (is (= (:doc_count info) 0))
    (is (= (:doc_del_count info) 0))
    (is (= (:update_seq info) 0)))
  ;; compact the db
  ;(is (= (couchdb/database-compact +test-db+) true)) ; this conflicts with database deletion in release 0.9
)


(deftest documents
  ;; first get list of documents
  (let [docs (couchdb/document-list +test-server+ +test-db+)]
    (is (zero? (count docs)))
    ;; now create a document with a server-generated ID
    (let [doc (couchdb/document-create +test-server+ +test-db+ {:foo 1})]
      (is (= (:foo (couchdb/document-get +test-server+
                                         +test-db+
                                         (:_id doc))) 1)))
    ;; and recheck the list of documents
    (let [new-docs (couchdb/document-list +test-server+ +test-db+)]
      (is (= 1 (count new-docs))))
    ;; now make a new document with an ID we choose
    (let [new-doc (couchdb/document-create +test-server+ +test-db+
                                           "foobar" {:foo 1})]
      ;; and recheck the list of documents
      (let [new-docs (couchdb/document-list +test-server+ +test-db+)]
        (is (= 2 (count new-docs)))
        (is (= 1 (count (filter #(= % "foobar") new-docs)))))
      ;; and try to get the document back from the server
      (is (= (:foo (couchdb/document-get +test-server+ +test-db+ :foobar)) 1))
      ;; now let's update our document
      (is (= (:foo (couchdb/document-update +test-server+ +test-db+
                                            "foobar"
                                            (assoc new-doc :foo 5)) 5)))
      ;; and grab it back from the server just to make sure
      (is (= (:foo (couchdb/document-get +test-server+ +test-db+
                                         :foobar) 5))))
    ;; create a document that we're going to delete
    (let [tbd (couchdb/document-create +test-server+ +test-db+ "tbd" {})]
      ;; now delete the document
      (is (= (couchdb/document-delete +test-server+ +test-db+ "tbd") true))
      ;; and check that it's gone
      (is (raised? couchdb/DocumentNotFound
                   (couchdb/document-get +test-server+ +test-db+ "tbd"))))))



(deftest attachments
  ;; list
  (is (= (couchdb/attachment-list +test-server+ +test-db+ "foobar") {}))
  ;; create
  (is (= (couchdb/attachment-create +test-server+ +test-db+
                                    "foobar" "my-attachment #1"
                                    "ATTACHMENT DATA" "text/plain")
         "my-attachment #1"))
  ;; get
  (is (= (couchdb/attachment-get +test-server+ +test-db+
                                 "foobar" "my-attachment #1")
         {:body-seq '("ATTACHMENT DATA")
          :content-type "text/plain"}))
  ;; re-check the list
  (let [atts (couchdb/attachment-list +test-server+ +test-db+ "foobar")
        att1 (get atts "my-attachment #1")]
    (is (= (count atts) 1))
    (is (not (nil? att1)))
    (is (= (select-keys att1 [:length :content_type :stub])
           {:length 15
            :content_type "text/plain"
            :stub true})))
  ;; delete
  (is (= (couchdb/attachment-delete +test-server+ +test-db+
                                    "foobar" "my-attachment #1") true))
  ;; re-check the list again
  (is (= (couchdb/attachment-list +test-server+ +test-db+ "foobar") {}))
  
  ;; create with InputStream
  (if-not (.exists (file *file*))
    (println "File " *file* "not found. Skipping InputStream-Test")
    (do
      (let [istream (FileInputStream. *file*)]
        (is (= (couchdb/attachment-create +test-server+ +test-db+
                                          "foobar" "my-attachment #2"
                                          istream "text/clojure")
               "my-attachment #2")))
      ;; get back the attachment we just created
      (let [istream (FileInputStream. *file*)]
        (is (= (couchdb/attachment-get +test-server+ +test-db+
                                       "foobar" "my-attachment #2")
               {:body-seq (line-seq (reader istream))
                :content-type "text/clojure"}))))))


(deftest documents-passing-map
  ;; test that all the document-related functions work the same whether they
  ;; get passed a string or a map as the document

  ;; create two new documents, one for testing string names, one for testing
  ;; passing the doc-map itself
  (let [regdoc (couchdb/document-create +test-server+ +test-db+
                                        "regdoc" {1 2 3 4 "baz" "quux"})
        mapdoc (couchdb/document-create +test-server+ +test-db+
                                        "mapdoc" {1 2 3 4 "baz" "quux"})]
    ;; update
    (let [regdoc-return (couchdb/document-update +test-server+ +test-db+
                                                 "regdoc"
                                                 (assoc regdoc :foo 42))
          mapdoc-return (couchdb/document-update +test-server+ +test-db+
                                                 mapdoc (assoc mapdoc :foo 42))]
      (is (= (dissoc regdoc-return :_id :_rev)
             (dissoc mapdoc-return :_id :_rev))))
    ;; get most recent revision
    (let [regdoc-return (couchdb/document-get +test-server+ +test-db+ "regdoc")
          mapdoc-return (couchdb/document-get +test-server+ +test-db+ mapdoc)]
      (is (= (dissoc regdoc-return :_id :_rev)
             (dissoc mapdoc-return :_id :_rev))))
    ;; get specific revision
    (let [regdoc-return (couchdb/document-get +test-server+ +test-db+
                                              "regdoc" (:_rev regdoc))
          mapdoc-return (couchdb/document-get +test-server+ +test-db+
                                              mapdoc (:_rev mapdoc))]
      (is (= (dissoc regdoc-return :_id :_rev)
             (dissoc mapdoc-return :_id :_rev))))
    ;; revisions
    (let [regdoc-return (couchdb/document-revisions +test-server+ +test-db+
                                                    "regdoc")
          mapdoc-return (couchdb/document-revisions +test-server+ +test-db+
                                                    mapdoc)]
      (is (= (count regdoc-return) (count mapdoc-return))))
    ;; deletion
    (let [regdoc-return (couchdb/document-delete +test-server+ +test-db+
                                                 "regdoc")
          mapdoc-return (couchdb/document-delete +test-server+ +test-db+
                                                 mapdoc)]
      ;; are both return values true?
      (is (= regdoc-return mapdoc-return true))
      ;; make sure fetching both documents gives a DocumentNotFound error
      (is (raised? couchdb/DocumentNotFound
                   (couchdb/document-get +test-server+ +test-db+ "regdoc")))
      (is (raised? couchdb/DocumentNotFound
                   (couchdb/document-get +test-server+ +test-db+ mapdoc))))))


(deftest attachments-passing-map
  ;; test that all the attachment-related functions work the same whether they
  ;; get passed a string or a map as the document
  
  ;; create two new documents, one for testing string names, one for testing
  ;; passing the doc-map itself
  (let [regdoc (couchdb/document-create +test-server+ +test-db+
                                        "regdoc" {1 2 3 4 "baz" "quux"})
        mapdoc (couchdb/document-create +test-server+ +test-db+
                                        "mapdoc" {1 2 3 4 "baz" "quux"})]
    ;; creating
    (let [regdoc-return (couchdb/attachment-create +test-server+ +test-db+
                                                   "regdoc" "att1"
                                                   "payload" "content/type")
          mapdoc-return (couchdb/attachment-create +test-server+ +test-db+
                                                   mapdoc "att1" "payload"
                                                   "content/type")]
      (is (= regdoc-return mapdoc-return)))
    ;; listing
    (let [regdoc-return (couchdb/attachment-list +test-server+ +test-db+
                                                 "regdoc")
          mapdoc-return (couchdb/attachment-list +test-server+ +test-db+
                                                 mapdoc)]
      (is (= regdoc-return mapdoc-return)))
    ;; fetching
    (let [regdoc-return (couchdb/attachment-get +test-server+ +test-db+
                                                "regdoc" "att1")
          mapdoc-return (couchdb/attachment-get +test-server+ +test-db+
                                                mapdoc "att1")]
      (is (= regdoc-return mapdoc-return)))
    ;; deleting
    (let [regdoc-return (couchdb/attachment-delete +test-server+ +test-db+
                                                   "regdoc" "att1")
          mapdoc-return (couchdb/attachment-delete +test-server+ +test-db+
                                                   mapdoc "att1")]
      ;; are both return values true?
      (is (= regdoc-return mapdoc-return true))
      ;; make sure fetching both attachments gives a DocumentNotFound error
      (is (raised? couchdb/AttachmentNotFound
                   (couchdb/attachment-get +test-server+ +test-db+
                                           "regdoc" "att1")))
      (is (raised? couchdb/AttachmentNotFound
                   (couchdb/attachment-get +test-server+ +test-db+
                                           mapdoc "att1"))))))


(deftest cleanup
  ;; be a good citizen and delete the database we use for testing
  (is (= (couchdb/database-delete +test-server+ +test-db+) true)))


(deftest error-checking
  ;; try to access an invalid database name
  (is (raised? couchdb/InvalidDatabaseName
               (couchdb/database-info +test-server+ "#one")))
  ;; try to get DB that doesn't exist
  (is (raised? couchdb/DatabaseNotFound
               (couchdb/database-info +test-server+ +test-db+)))
  ;; create our test-db
  (is (= (couchdb/database-create +test-server+ +test-db+) +test-db+))
  ;; try to create it again
  (is (raised? couchdb/PreconditionFailed
               (couchdb/database-create +test-server+ +test-db+)))
  ;; try to grab non-extant document
  (is (raised? couchdb/DocumentNotFound
               (couchdb/document-get +test-server+ +test-db+ "foo")))
  ;; create a document with invalid JSON
  (is (raised? couchdb/ServerError
               (couchdb/document-create +test-server+ +test-db+
                                        "not a JSON object")))
  ;; create a document for reals this time
  (let [doc (couchdb/document-create +test-server+ +test-db+
                                     "foo" {:foo 42})]
    (is (= (:foo doc) 42))
    (is (= (:_id doc) "foo"))
    ;; try to update the document without sending the version
    (is (raised? couchdb/ResourceConflict
                 (couchdb/document-update +test-server+ +test-db+
                                          "foo" {:foo 43})))
    ;; update the document for real
    (couchdb/document-update +test-server+ +test-db+
                             "foo" (assoc doc :foo 43))
    ;; check that it updated
    (let [new-doc (couchdb/document-get +test-server+ +test-db+ "foo")]
      (is (= (:foo new-doc) 43))))
  ;; create an initial version of a document
  (let [first-rev (couchdb/document-create
                   +test-server+ +test-db+
                   "bam" {:answer "one"})]
    ;; test that we can just update the document straight up
    (is (= (:answer (couchdb/document-update
                     +test-server+ +test-db+
                     "bam" (assoc first-rev :answer "two")) "two")))
    ;; now try to insert with the wrong revision
    (is (raised? couchdb/ResourceConflict
                 (couchdb/document-update
                  +test-server+ +test-db+
                  "bam" (assoc first-rev :answer "three")))))
  ;; try to delete an attachment that doesn't exist
  (is (= true (couchdb/attachment-delete +test-server+ +test-db+ "bam" "f"))))

(defn test-db-fixture [db f]
     (try
       (couchdb/database-create +test-server+ db)
       (f)
       (finally
	(couchdb/database-delete +test-server+ db))))

(deftest replication-test
  (couchdb/database-replicate +test-server+ +test-db+
		      +test-server+  +test-db2+)
  (is (= (couchdb/document-list +test-server+ +test-db+)
	 (couchdb/document-list +test-server+  +test-db2+))))

;;; test-ns-hook is used to run tests in the specified order
(defn test-ns-hook []
  (databases)
  (documents)
  (attachments)
  (documents-passing-map)
  (attachments-passing-map)
  (test-db-fixture +test-db2+ replication-test)
  (cleanup)
  (error-checking)
  (cleanup))

