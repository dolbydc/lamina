;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the

;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.test.pipeline
  (:use [lamina.core])
  (:use [clojure.test])
  (:import [java.util.concurrent
	    TimeoutException
	    CountDownLatch
	    TimeUnit]))

(defn test-pipeline [pipeline expected-result]
  (is (= expected-result (wait-for-result (pipeline 0) 5000))))

(def slow-inc
  (blocking
    (fn [x]
      (Thread/sleep 10)
      (inc x))))

(defn assert-failure [pipeline]
  (try
    (wait-for-result (pipeline 0) 100)
    (catch TimeoutException e
      (is false))
    (catch Exception e
      (is true))))

(defn fail [_]
  (throw (Exception. "boom")))

(def slow-fail
  (blocking (fn [_] (fail))))

(defn fail-times [n]
  (let [counter (atom n)]
    (fn [x]
      (swap! counter dec)
      (if (pos? @counter)
	(fail nil)
	x))))

(declare pipe-a)
(def pipe-b (pipeline inc #(if (< % 10) (redirect pipe-a %) %)))
(def pipe-a (pipeline inc #(if (< % 10) (redirect pipe-b %) %)))

;;;

(deftest test-basic-pipelines
  (test-pipeline (apply pipeline (take 1e3 (repeat inc))) 1e3)
  (test-pipeline (apply pipeline (take 1e3 (repeat (blocking inc)))) 1e3)
  (test-pipeline (apply pipeline (take 100 (repeat slow-inc))) 100))

(deftest test-nested-pipelines
  (test-pipeline (pipeline inc (pipeline inc (pipeline inc) inc) inc) 5))

(deftest test-redirected-pipelines
  (test-pipeline (pipeline inc inc #(redirect (pipeline inc inc inc) %)) 5)
  (test-pipeline pipe-b 10)

  (let [cat (fn [x] (fn [s] (conj s x)))]
    (test-pipeline
      (pipeline (fn [_] [])
	(cat "a")
	(pipeline
	  (cat "b")
	  #(if (< (count %) 3)
	     (restart %)
	     %)
	  (cat "c"))
	(cat "d"))
      (map str (seq "abbcd")))))

(deftest test-error-propagation
  (assert-failure (pipeline :error-handler (fn [_]) fail))
  (assert-failure (pipeline :error-handler (fn [_]) inc fail))
  (assert-failure (pipeline :error-handler (fn [_]) inc fail inc))
  (assert-failure (pipeline :error-handler (fn [_]) slow-inc slow-fail))
  (assert-failure (pipeline :error-handler (fn [_]) inc (pipeline :error-handler (fn [_ _]) inc fail) inc))
  (assert-failure (pipeline :error-handler (fn [_]) inc #(redirect (pipeline :error-handler (fn [_ _]) inc fail) %))))

(deftest test-redirection-and-error-handlers

  (let [n (atom 0)
	f (fn [_] (swap! n inc))]
    (run-pipeline n
      :error-handler (fn [_])
      (pipeline :error-handler f
	(pipeline :error-handler f
	  (pipeline :error-handler f
	    fail))))
    (is (= 3 @n)))

  (let [n (atom [])
	f (fn [val] (fn [_] (swap! n conj val)))]
    (run-pipeline n
      :error-handler (fn [_])
      (pipeline :error-handler (f 1)
	(fn [x]
	  (redirect
	    (pipeline :error-handler (f 2)
	      (fn [x]
		(redirect
		  (pipeline :error-handler (f 3)
		    fail)
		  x)))
	    x))))
    (is (= [3] @n))))

(deftest test-error-handling

  (test-pipeline
    (pipeline :error-handler (fn [ex] (redirect (pipeline inc) 0))
      inc
      fail)
    1)

  (test-pipeline
    (pipeline :error-handler (fn [ex] (restart))
      inc
      (fail-times 3)
      inc)
    2)

  (test-pipeline
    (pipeline
      inc
      (pipeline :error-handler (fn [ex] (restart))
	inc
	(fail-times 3))
      inc)
    3))

(deftest test-tail-recursion
  
  (let [ch (apply closed-channel (range 1e4))]
    (run-pipeline ch
      read-channel
      (fn [x]
	(when-not (closed? ch)
	  (restart)))))

  (run-pipeline 1e4
    #(when (pos? %)
       (restart (dec %))))

  ;;TODO: excessive failures should stop the pipeline at some point
  (run-pipeline nil
    :error-handler (fn [_] (restart))
    (fail-times 1e4)))