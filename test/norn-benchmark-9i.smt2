(set-logic QF_S)

(declare-fun var_0 () String)
(declare-fun var_1 () String)
(declare-fun var_2 () String)
(declare-fun var_3 () String)
(declare-fun var_4 () String)
(declare-fun var_5 () String)
(declare-fun var_6 () String)
(declare-fun var_7 () String)
(declare-fun var_8 () String)

(assert (str.in.re var_0 (str.to.re "abc")))
(assert (or (str.in.re var_1 (str.to.re "abd"))
            (str.in.re var_1 (str.to.re "ab"))
            (str.in.re var_1 (str.to.re "abcd"))))
(assert (not (= var_0 var_1)))
(check-sat)
