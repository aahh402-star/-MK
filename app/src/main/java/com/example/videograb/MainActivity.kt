<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center_horizontal"
    android:padding="20dp"
    android:background="#121212">

    <!-- عنوان التطبيق -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="تنزيل 2في1"
        android:textSize="24sp"
        android:textStyle="bold"
        android:textColor="#FFFFFF"
        android:layout_marginBottom="30dp"/>

    <!-- حقل إدخال الرابط -->
    <EditText
        android:id="@+id/urlInput"
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:hint="الصق رابط الفيديو أو الصوت هنا..."
        android:textColor="#FFFFFF"
        android:textColorHint="#888888"
        android:background="#1E1E1E"
        android:paddingHorizontal="15dp"
        android:layout_marginBottom="15dp"/>

    <!-- زر اللصق من الحافظة -->
    <Button
        android:id="@+id/btnPaste"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="لصق من الحافظة"
        android:backgroundTint="#333333"
        android:textColor="#FFFFFF"
        android:layout_marginBottom="10dp"/>

    <!-- زر السجل -->
    <Button
        android:id="@+id/btnHistory"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="السجل"
        android:backgroundTint="#333333"
        android:textColor="#FFFFFF"
        android:layout_marginBottom="20dp"/>

    <!-- زر مشغل الموسيقى والمكتبة الشاملة (تحت السجل واللصق مباشرة) -->
    <Button
        android:id="@+id/btnOpenMusicPlayer"
        android:layout_width="match_parent"
        android:layout_height="55dp"
        android:text="🎵 مشغل الموسيقى والمكتبة"
        android:backgroundTint="#FF9800"
        android:textColor="#FFFFFF"
        android:textStyle="bold"
        android:textSize="16sp"/>

</LinearLayout>
