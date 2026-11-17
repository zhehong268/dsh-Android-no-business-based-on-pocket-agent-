package dev.pocketagent.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class QuizLabelsTest {
    @Test fun submittingOneAnswerIsPartOfAnswering() {
        assertEquals(QuizTap.AUTO, QuizLabels.classify("提交答案"))
        assertEquals(QuizTap.AUTO, QuizLabels.classify("下一题"))
        assertEquals(QuizTap.AUTO, QuizLabels.classify("确定"))
        assertEquals(QuizTap.AUTO, QuizLabels.classify("确认选择"))
        assertEquals(QuizTap.AUTO, QuizLabels.classify("Next"))
    }

    @Test fun handingInThePaperStillAsks() {
        assertEquals(QuizTap.FINAL, QuizLabels.classify("交卷"))
        assertEquals(QuizTap.FINAL, QuizLabels.classify("提交答卷"))
        assertEquals(QuizTap.FINAL, QuizLabels.classify("结束考试"))
    }

    @Test fun spendingMoneyIsNeverPartOfAnswering() {
        assertEquals(QuizTap.NONE, QuizLabels.classify("提交订单"))
        assertEquals(QuizTap.NONE, QuizLabels.classify("立即购买"))
        assertEquals(QuizTap.NONE, QuizLabels.classify("确认支付"))
        assertEquals(QuizTap.NONE, QuizLabels.classify("充值"))
    }

    @Test fun unrelatedLabelsAreLeftToTheGate() {
        assertEquals(QuizTap.NONE, QuizLabels.classify("设置"))
        assertEquals(QuizTap.NONE, QuizLabels.classify("返回"))
        assertEquals(QuizTap.NONE, QuizLabels.classify(""))
    }

    @Test fun fullWidthSpacingAndCaseDoNotMatter() {
        assertEquals(QuizTap.AUTO, QuizLabels.classify("  确认答案  "))
        assertEquals(QuizTap.AUTO, QuizLabels.classify("NEXT"))
    }
}
