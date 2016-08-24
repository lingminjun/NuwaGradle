# NuwaGradle
此插件基于源码https://github.com/jasonross/NuwaGradle修改

###新的特性：
1、支持gradle2.0以上版，1.5以下版本也兼容
2、使用Android Studio2.x版本直接打包或者调试时不支持patch，因为Android Studio默认会开启Instant Run，导致无法patch所有class

###如何使用：
1、下载源码NuwaGradle源码，用Android Studio打开
2、运行publishing下的publishToMavenLocal task，在本地maven仓库的对应目录下就生成了jar(.m2/repository/cn/jiajixin/nuwa/gradle/1.3.1/gradle-1.3.1.jar).

###申明：
原作者一直没有更新nuwa.jar，仍然保留支持gradle 1.5以下版本，因团队开发无法享受gradle新版本特性（提速等），故做此修改，其代码基本保留原始结构，若修改有误请指正。