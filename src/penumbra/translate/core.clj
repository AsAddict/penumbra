;   Copyright (c) Zachary Tellman. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns penumbra.translate.core
  (:use [clojure.contrib.pprint])
  (:use [clojure.contrib.def :only (defmacro- defvar defvar-)])
  (:import (java.text ParseException))
  (:import (java.io StringWriter))
  (:require [clojure.zip :as zip]))

;;;

(defvar *generator* nil
  "Anything returned by this is prepended to the beginning of the expression.
  Currently only used for imports, could also be used for anonymous functions.")
(defvar *parser* nil
  "Returns a string in the native language for the given s-expression.")
(defvar *transformer* nil
  "Macros, applied from leaf to root across entire expression.")
(defvar *inspector* nil
  "Returns the type of the expression.  Applied as :tag metadata.")
(defvar *tagger* nil
  "Specialized macro.  Should set :assignment metadata to true where a variable is being assigned, and pull
  type from r-value to l-value.")

;;;

(defn seq-wrap [s]
  (if (sequential? s) s (list s)))

(defn realize [s]
  (if (seq? s) (doall s) s))

(defn- str-pprint [expr]
  (let [s (new StringWriter)]
    (pprint expr s)
    (str s)))

(defn indent
  "Indents every line two spaces."
  [s]
  (let [lines (seq (.split s "\n"))]
    (str (apply str (interpose "\n" (map #(str "  " %) lines))) "\n")))

(defmacro- defn-try [name fun message]
  `(defn ~name [expr#]
    (try
      (~fun expr#)
      (catch ParseException pe#
        (throw pe#))
      (catch Exception e#
        (throw (ParseException. (str "\n" ~message "\n" (str-pprint expr#) (.getMessage e#)) 0))))))

(defmacro- defn-try-
  [name fun message]
  (list `defn-try (with-meta name (assoc (meta name) :private true)) fun message))

;;;

(defn-try- try-transform
  #(realize (*transformer* %))
  "Error while transforming")

(defn- mimic-expr [a b]
  (with-meta
    (cond
      (vector? a) (vec b)
      (map? a) (hash-map b)
      :else b)
    (merge ^a ^b)))

(defn tree-filter [pred expr]
  (filter pred (tree-seq sequential? seq expr)))

(defn- do-tree* [x fun depth]
  (cond
    (not (sequential? x))
      (fun x depth)
    :else
      (do
        (fun x depth)
        (doseq [i x] (do-tree* i fun (inc depth))))))
            
(defn- do-tree [x fun]
  (do-tree* x fun 0))
  
(defn- tree-map*
  "A post-order recursion, so that we can build transforms upwards."
  [x fun]
  (cond
    (not (sequential? x)) (fun x)
    (empty? x) ()
    :else (mimic-expr x (fun (map #(tree-map* % fun) x)))))

(defn tree-map [x fun]
  (loop [x (tree-map* x fun)]
    (let [x* (tree-map* x fun)]
      (if (= x x*) x (recur x*)))))

(defn print-tree [expr]
  (println
   (with-out-str
     (do-tree
      expr
      #(print
        (apply str (realize (take (* 2 %2) (repeat "  "))))
        (realize %) "^" ^(realize %) "\n")))))

;;;

(defn- transform-exprs [expr]
  (if *transformer*
    (tree-map expr try-transform)
    expr))

;;;

(defn-try try-generate
  #(realize (*generator* %))
  "Error while generating")

(defn generate-exprs [expr]
  (loop [body (list expr)
         tail (-> expr try-generate transform-exprs)]
    (if (empty? tail)
     body
     (recur
       (concat body (if (seq? (ffirst tail)) (apply concat tail) tail))
       (-> tail try-generate transform-exprs)))))

;;;

(defn meta? [expr]
  (instance? clojure.lang.IMeta expr))

(defn-try try-inspect
  #(realize
    (if (meta? %)
      (with-meta % (assoc ^% :tag (or (*inspector* %) (:tag ^%))))
      %))
  "Error while inferring type")

(defn inspect-exprs [expr]
  (if *inspector*
    (tree-map expr try-inspect)
    expr))

;;;

(defn tag-first-appearance [expr]
  (let [vars (atom #{})]
    (tree-map
     expr
     (fn [x]
       (if (and (symbol? x) (:assignment ^x) (not (@vars x)))
        (do
          (swap! vars #(conj % x))
          (with-meta x (assoc ^x :first-appearance true)))
        x)))))

(defn-try try-tag
  #(realize (*tagger* %))
  "Error while tagging")

(defn- tag-exprs [expr]
  (if *tagger*
    (-> expr (tree-map try-tag) tag-first-appearance)
    expr))

;;;

(defn typeof [expr]
  (cond
    (integer? expr) 'int
    (float? expr) 'float
    (not (meta? expr)) nil
    (:numeric-value ^expr) (typeof (:numeric-value ^expr))
    (:tag ^expr) (:tag ^expr)))

(defn declared-vars [expr]
  (distinct (tree-filter #(:assignment ^%) expr)))

(defn var-type
  "Determine type, if possible, of var within expr"
  [var expr]
  (let [vars (tree-filter #(or (= var %) (and (meta? %) (= var (:defines ^%)))) expr)
        types (distinct (filter identity (map typeof vars)))]
    (cond
      (empty? types)
        nil
      (= 1 (count types))
        (first types)
      :else
        (throw (ParseException. (str "Ambiguous type for " var ", cannot decide between " (with-out-str (prn types))) 0)))))

(defn- tag-var
  "Add :tag metadata to all instances of var in expr"
  [var type expr]
  (tree-map
   expr
   #(if (not= var %) % (with-meta % (assoc ^% :tag type)))))

(defn infer-types
  "Repeatedly applies inspect-exprs and tag-var until everything is typed"
  [expr]
  (loop [expr expr, iterations 0]
    (let [vars          (declared-vars expr)
          types         (zipmap vars (map #(var-type % expr) vars))
          known-types   (filter (fn [[k v]] v) types)
          unknown-types (filter (fn [[k v]] (not v)) types)
          expr*         (inspect-exprs (reduce (fn [x [k v]] (tag-var k v x)) expr known-types))]
      (if (< 10 iterations)
        (throw (Exception. (str "Unable to determine type of " (with-out-str (prn (keys unknown-types)))))))
      (print-tree expr*)
      (if (empty? unknown-types)
        expr*
        (recur expr* (inc iterations))))))
        
;;;

(defn parse-lines
  "Maps *parser* over a list of s-expressions, filters out empty lines,
  and optionally adds a terminating character."
  ([exprs] (parse-lines exprs ""))
  ([exprs termination]
     (if (and
          (seq? exprs)
          (= 1 (count exprs))
          (seq? (first exprs)))
      (parse-lines (first exprs) termination)
      (let [exprs             (if (seq? (first exprs)) exprs (list exprs))
            translated-exprs  (map #(*parser* %) exprs)
            filtered-exprs    (filter #(not= (.trim %) "") translated-exprs)
            terminated-exprs  (map #(if (.endsWith % "\n") % (str % termination "\n")) filtered-exprs)]
        (apply str terminated-exprs)))))

(defn-try parse
  #(realize (*parser* %))
  "Error while parsing")

;;;

(defn translate-expr
  "Applies all the multi-fns, in the appropriate order."  
  [expr]
  (-> expr
    transform-exprs
    generate-exprs reverse
    tag-exprs
    infer-types
    parse-lines))
