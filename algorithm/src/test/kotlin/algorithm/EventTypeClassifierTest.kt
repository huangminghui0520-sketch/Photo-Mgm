// EventTypeClassifier 单元测试（§3.3 / 附录 A / §9 测试要点）
package algorithm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EventTypeClassifierTest {
    @Test fun `主线巡查字样直接返回`() {
        assertEquals("主线巡查", EventTypeClassifier.classify("到达C收费站，途经，主线巡查，路况正常"))
    }

    @Test fun `无命中兜底主线巡查`() {
        assertEquals("主线巡查", EventTypeClassifier.classify("巡查人员张某某、李某某对全线进行巡查，路况正常"))
    }

    @Test fun `交安设施专项排查_组合条件命中`() {
        assertEquals("交安设施专项排查", EventTypeClassifier.classify("对K182+000M处交安设施进行专项排查，发现标志牌倾斜"))
    }

    @Test fun `照明专项排查不归交安专项`() {
        assertEquals("照明设施排查", EventTypeClassifier.classify("对K178+000M至K180+000M路段照明设施进行专项排查，发现高杆灯一盏不亮"))
    }

    @Test fun `事故需事故核心词`() {
        assertEquals("交通事故处置", EventTypeClassifier.classify("接报K145+000M处发生两车追尾事故，立即前往"))
    }

    @Test fun `故障车占用应急车道归车辆故障非交通违法`() {
        assertEquals("车辆故障救援监督", EventTypeClassifier.classify("在K148+500M处发现一辆故障车，车牌粤C44444，占用应急车道"))
    }

    @Test fun `边坡滑坡占用应急车道归高边坡非交通违法`() {
        assertEquals("高边坡巡查", EventTypeClassifier.classify("在K162+000M处发生边坡滑坡，泥土占用应急车道"))
    }

    @Test fun `收车`() {
        assertEquals("收车/返回", EventTypeClassifier.classify("返回A收费管理中心，结束巡查，收车"))
    }

    @Test fun `返回施工点继续检查归涉路施工非收车`() {
        assertEquals("涉路施工监管", EventTypeClassifier.classify("返回K164+000M施工点继续检查，检查基坑施工"))
    }

    @Test fun `收费系统故障归收费设施故障`() {
        assertEquals("收费设施故障", EventTypeClassifier.classify("巡查至K170+000M收费站，发现收费系统故障，栏杆机无法抬杆"))
    }

    @Test fun `汛期排水沟归汛期专项`() {
        assertEquals("汛期/恶劣天气专项巡查", EventTypeClassifier.classify("汛期巡查，检查K186+000M处排水沟，发现堵塞"))
    }

    @Test fun `一台风机不触发汛期台风复合词`() {
        assertEquals("隧道机电设施巡查", EventTypeClassifier.classify("检查隧道通风设施，发现一台风机故障"))
    }

    @Test fun `消防设施专项检查`() {
        assertEquals("消防设施专项检查", EventTypeClassifier.classify("对K188+000M处消防设施进行专项检查，发现灭火器压力不足"))
    }

    @Test fun `隧道渗漏水`() {
        assertEquals("隧道渗漏水处置", EventTypeClassifier.classify("巡查至K164+000M隧道，发现隧道渗漏水"))
    }

    @Test fun `隧道衬砌裂缝归隧道结构`() {
        assertEquals("隧道结构巡查", EventTypeClassifier.classify("检查隧道衬砌，发现裂缝"))
    }

    @Test fun `护栏损坏归交安设施巡查`() {
        assertEquals("交安设施巡查", EventTypeClassifier.classify("到达K138+700M处，检查交安设施，发现护栏损坏"))
    }

    @Test fun `抛洒物归路面障碍物清理`() {
        assertEquals("路面障碍物清理", EventTypeClassifier.classify("发现路面有抛洒物，已清理"))
    }

    @Test fun `节假日保畅优先于拥堵缓行`() {
        assertEquals("节假日保畅巡查", EventTypeClassifier.classify("节假日保畅巡查，在K190+000M处疏导车流，现场缓行"))
    }

    @Test fun `路产损坏调查护栏被撞`() {
        assertEquals("路产损坏调查", EventTypeClassifier.classify("路产损坏调查，发现K192+000M处护栏被撞损坏"))
    }

    @Test fun `桥下违法搭建归桥下违法处置`() {
        assertEquals("桥下空间违法处置", EventTypeClassifier.classify("发现桥下空间有违法搭建，已制止"))
    }

    @Test fun `联合巡查`() {
        assertEquals("联合巡查", EventTypeClassifier.classify("联合养护在K200+000M处开展联合巡查"))
    }
}
