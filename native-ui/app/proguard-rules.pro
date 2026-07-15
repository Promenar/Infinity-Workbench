# Nexara Native ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlinx.serialization.** { *; }

# R8 bytecode optimization to strip logging in production
-assumenosideeffects class com.promenar.nexara.utils.NexaraLogger {
    public void log(java.lang.String);
    public void logError(java.lang.String, java.lang.Throwable);
    public void metro(java.lang.String, java.lang.String);
}

# 精确覆盖统一日志器内部使用的平台日志重载，避免未来残留调用进入 Release。
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String, java.lang.Throwable);
}

# Release 禁止直接打印异常堆栈；源码契约测试同时禁止业务源码出现该调用。
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# XMLBeans/OOXML 类型系统入口会根据 .xsb 元数据加载。
# 不得全成员 keep wordprocessingml：poi-ooxml-lite 有意省略的高级 schema
# 仍出现在部分接口签名中，全 keep 会将这些不可达方法错误保活。
# Log4j 的 AbstractLogger 会反射实例化以下内建消息工厂；R8 无法静态识别该调用。
# 仅保留三个反射目标的 public 无参构造器并禁止类抽象化，类名仍允许混淆。
-keepclassmembers,allowobfuscation class org.apache.logging.log4j.message.DefaultFlowMessageFactory {
    public <init>();
}
-keepclassmembers,allowobfuscation class org.apache.logging.log4j.message.ReusableMessageFactory {
    public <init>();
}
-keepclassmembers,allowobfuscation class org.apache.logging.log4j.message.ParameterizedMessageFactory {
    public <init>();
}

# Commons Compress 的 ExtraFieldUtils 会反射实例化内建 ZipExtraField 实现。
# 仅保留该接口实现的 public 无参构造器，允许类名混淆；不保留其它成员。
-keepclassmembers,allowobfuscation class org.apache.commons.compress.archivers.zip.** implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}

-keep,allowoptimization class org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder { *; }
-keepnames class org.openxmlformats.schemas.wordprocessingml.x2006.main.**
-keep,allowoptimization class org.openxmlformats.schemas.wordprocessingml.x2006.main.impl.** {
    public <init>(org.apache.xmlbeans.SchemaType);
}
-keep,allowoptimization class org.openxmlformats.schemas.officeDocument.x2006.relationships.** { *; }

# 以下类型均属于依赖库的可选集成，Nexara 的 Android 文本提取路径不可达：
# Log4j OSGi 发现注解、PDFBox JP2 图像解码、POI AWT 桌面渲染、
# XMLBeans StAX/Saxon XPath/XQuery，以及 SLF4J 的可选静态 binder。
# 保持精确类名，严禁扩大为整库或全局 -dontwarn。
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn java.awt.Color
-dontwarn java.awt.color.ColorSpace
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.Dimension2D
-dontwarn java.awt.geom.Path2D
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ColorModel
-dontwarn java.awt.image.ComponentColorModel
-dontwarn java.awt.image.DirectColorModel
-dontwarn java.awt.image.IndexColorModel
-dontwarn java.awt.image.PackedColorModel
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn net.sf.saxon.Configuration
-dontwarn net.sf.saxon.dom.DOMNodeWrapper
-dontwarn net.sf.saxon.dom.DocumentWrapper
-dontwarn net.sf.saxon.dom.NodeOverNodeInfo
-dontwarn net.sf.saxon.lib.ConversionRules
-dontwarn net.sf.saxon.ma.map.HashTrieMap
-dontwarn net.sf.saxon.om.GroundedValue
-dontwarn net.sf.saxon.om.Item
-dontwarn net.sf.saxon.om.NamespaceUri
-dontwarn net.sf.saxon.om.NodeInfo
-dontwarn net.sf.saxon.om.Sequence
-dontwarn net.sf.saxon.om.SequenceTool
-dontwarn net.sf.saxon.om.StructuredQName
-dontwarn net.sf.saxon.query.DynamicQueryContext
-dontwarn net.sf.saxon.query.StaticQueryContext
-dontwarn net.sf.saxon.query.XQueryExpression
-dontwarn net.sf.saxon.str.StringView
-dontwarn net.sf.saxon.str.UnicodeString
-dontwarn net.sf.saxon.sxpath.IndependentContext
-dontwarn net.sf.saxon.sxpath.XPathDynamicContext
-dontwarn net.sf.saxon.sxpath.XPathEvaluator
-dontwarn net.sf.saxon.sxpath.XPathExpression
-dontwarn net.sf.saxon.sxpath.XPathStaticContext
-dontwarn net.sf.saxon.sxpath.XPathVariable
-dontwarn net.sf.saxon.tree.wrapper.VirtualNode
-dontwarn net.sf.saxon.type.BuiltInAtomicType
-dontwarn net.sf.saxon.type.ConversionResult
-dontwarn net.sf.saxon.value.AnyURIValue
-dontwarn net.sf.saxon.value.AtomicValue
-dontwarn net.sf.saxon.value.BigDecimalValue
-dontwarn net.sf.saxon.value.BigIntegerValue
-dontwarn net.sf.saxon.value.BooleanValue
-dontwarn net.sf.saxon.value.CalendarValue
-dontwarn net.sf.saxon.value.DateTimeValue
-dontwarn net.sf.saxon.value.DateValue
-dontwarn net.sf.saxon.value.DoubleValue
-dontwarn net.sf.saxon.value.DurationValue
-dontwarn net.sf.saxon.value.FloatValue
-dontwarn net.sf.saxon.value.GDateValue
-dontwarn net.sf.saxon.value.GDayValue
-dontwarn net.sf.saxon.value.GMonthDayValue
-dontwarn net.sf.saxon.value.GMonthValue
-dontwarn net.sf.saxon.value.GYearMonthValue
-dontwarn net.sf.saxon.value.GYearValue
-dontwarn net.sf.saxon.value.HexBinaryValue
-dontwarn net.sf.saxon.value.Int64Value
-dontwarn net.sf.saxon.value.ObjectValue
-dontwarn net.sf.saxon.value.QNameValue
-dontwarn net.sf.saxon.value.SaxonDuration
-dontwarn net.sf.saxon.value.SaxonXMLGregorianCalendar
-dontwarn net.sf.saxon.value.StringValue
-dontwarn net.sf.saxon.value.TimeValue
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.slf4j.impl.StaticLoggerBinder
