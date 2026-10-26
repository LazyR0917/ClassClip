plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.classclip"
    compileSdk {
        version = release(37)
    }

    // 资源按功能分目录存放（timetable/todo/habit/plan/common），
    // 每个目录内含 layout/drawable/anim 等合法资源类型子目录，作为额外资源源合并进 R
    sourceSets {
        getByName("main") {
            res.srcDirs(
                "src/main/res/timetable",
                "src/main/res/todo",
                "src/main/res/habit",
                "src/main/res/plan",
                "src/main/res/common",
                "src/main/res"
            )
        }
    }

    defaultConfig {
        applicationId = "com.example.classclip"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // Apache POI 的 META-INF 里有同名清单文件，打包时排除避免冲突
    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.viewpager2)
    implementation(libs.recyclerview)
    implementation(libs.drawerlayout)
    // Apache POI：解析教务系统导出的 .xls 课表（仅核心库，不含 ooxml/xmlbeans）
    implementation("org.apache.poi:poi:3.17")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}