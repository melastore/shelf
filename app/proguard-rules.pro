-keepattributes *Annotation*, RuntimeVisible*Annotations, AnnotationDefault

# kotlinx.serialization keeps generated serializers referenced only via reflection.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
	static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
	static **$* *;
}
-keepclassmembers class <2>$<3> {
	kotlinx.serialization.KSerializer serializer(...);
}
