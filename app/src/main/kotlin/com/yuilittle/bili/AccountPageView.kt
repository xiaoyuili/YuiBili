package com.yuilittle.bili

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.util.Locale

/**
 * 「我的」页面：
 * - 未登录：极简扫码登录页（Logo + 二维码卡片 + 下载/刷新操作 + 外观切换）。
 * - 已登录：精致资料卡（头像/昵称/UID/等级徽章 + 关注/粉丝/硬币）+ 外观（夜间模式圆形转场开关）+ 常用功能列表。
 */
class AccountPageView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appearanceUpdates = ArrayList<() -> Unit>()
    private var appearancePopup: android.widget.PopupWindow? = null

    // ── 页面容器 ──────────────────────────────────────────────────
    private val loginPage = LinearLayout(context)
    private val profilePage = LinearLayout(context)

    // ── 扫码登录控件 ──────────────────────────────────────────────
    private val qrImage = ImageView(context)
    private val qrStatus = TextView(context)
    private var qrKey = ""
    private var qrPolling = false
    private var active = false
    private var entrancePlayed = false
    private var qrRequestGeneration = 0
    private var qrPollGeneration = 0
    private var qrBitmap: Bitmap? = null
    private var qrContent: String = ""

    // ── 资料卡片控件 ──────────────────────────────────────────────
    private val cardAvatar = RoundAvatar(context)
    private val cardName = TextView(context)
    private val cardUid = TextView(context)
    private val cardBadges = LinearLayout(context)
    private val cardFollowing = TextView(context)
    private val cardFollower = TextView(context)
    private val cardCoins = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        loginPage.visibility = View.VISIBLE
        profilePage.visibility = View.GONE

        buildLoginPage()
        buildProfilePage()

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val content = FrameLayout(context)
        content.addView(
            loginPage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            profilePage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildLayoutAdaptRow(): LinearLayout {
        val valueLabel = TextView(context).apply {
            text = LayoutMode.current.label
            textSize = 13f
            typeface = Ds.body(true)
            includeFontPadding = false
            setTextColor(COLOR_INK)
        }
        val dropdownAnchor = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(10), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(valueLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(
                LayoutMode.dropdownChevronIcon(context, COLOR_MUTED),
                LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(4) }
            )
        }
        val openDropdown = View.OnClickListener {
            appearancePopup?.dismiss()
            appearancePopup = LayoutMode.showModeDropdown(context, dropdownAnchor) { picked ->
                if (LayoutMode.setMode(context, picked)) {
                    valueLabel.text = picked.label
                    Toast.makeText(context, "已切换为${picked.label}外观", Toast.LENGTH_SHORT).show()
                    (context as? Activity)?.let { act ->
                        @Suppress("DEPRECATION")
                        act.overridePendingTransition(0, 0)
                        act.recreate()
                    }
                }
            }
            appearancePopup?.setOnDismissListener { appearancePopup = null }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.lg), 0, dp(Ds.Space.lg), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener(openDropdown)
            contentDescription = "外观适配：${LayoutMode.current.label}"
            val iconHost = FrameLayout(context).apply {
                background = Ds.card(context, COLOR_ROSE_SOFT, null, Ds.Radius.chip, 0)
                addView(
                    LayoutMode.deviceAdaptIcon(context, COLOR_ROSE),
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                )
            }
            addView(iconHost, LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(Ds.Space.md) })
            addView(TextView(context).apply {
                text = "外观适配"
                textSize = 15f
                typeface = Ds.body(true)
                setTextColor(COLOR_INK)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(dropdownAnchor, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        bindRowPress(row)
        return row
    }

    /** 构造包含「夜间模式」与「外观适配」的统一外观卡片（登录态与游客态均可用）。 */
    private fun buildAppearanceCard(): LinearLayout {
        val appearanceCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(0, dp(2), 0, dp(2))
        }
        if (!LayoutMode.isTablet) {
            val darkSwitch = PillSwitch(context, Theme.isDark)
            val darkRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ds.Space.lg), 0, dp(Ds.Space.lg), 0)
                isClickable = true
                isFocusable = true
                addView(iconChip(MenuIconView.MOON),
                    LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(Ds.Space.md) })
                addView(TextView(context).apply {
                    text = "夜间模式"
                    textSize = 15f
                    typeface = Ds.body(true)
                    setTextColor(COLOR_INK)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(darkSwitch, LayoutParams(dp(48), dp(28)))
                setOnClickListener { toggleDarkMode(darkSwitch, darkSwitch) }
                darkSwitch.setOnClickListener { toggleDarkMode(darkSwitch, darkSwitch) }
            }
            bindRowPress(darkRow)
            appearanceCard.addView(darkRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
            appearanceCard.addView(View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context)).apply {
                    leftMargin = dp(Ds.Space.lg + 34 + Ds.Space.md)
                    rightMargin = dp(Ds.Space.lg)
                })
        }
        appearanceCard.addView(buildLayoutAdaptRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        fun addChoice(title: String, icon: Int, labels: List<String>, current: () -> Int, select: (Int) -> Unit) {
            appearanceCard.addView(View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context)).apply {
                    leftMargin = dp(Ds.Space.lg + 34 + Ds.Space.md)
                    rightMargin = dp(Ds.Space.lg)
                })
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(Ds.Space.lg), dp(6), dp(Ds.Space.lg), dp(6))
                isFocusable = true
            }
            row.addView(iconChip(icon), LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(Ds.Space.md) })
            row.addView(TextView(context).apply {
                text = title
                textSize = 15f
                typeface = Ds.body(true)
                setTextColor(COLOR_INK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val value = TextView(context).apply {
                textSize = 13f
                typeface = Ds.body(true)
                includeFontPadding = false
                setTextColor(COLOR_INK)
                maxLines = 1
                maxWidth = dp(130)
                ellipsize = TextUtils.TruncateAt.END
            }
            val anchor = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(10), dp(6))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(value)
                addView(LayoutMode.dropdownChevronIcon(context, COLOR_MUTED), LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(4) })
            }
            row.addView(anchor, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            val update = {
                value.text = labels[current()]
                row.contentDescription = "$title：${value.text}"
            }
            appearanceUpdates.add(update)
            update()
            row.setOnClickListener {
                showAppearanceChoices(anchor, title, labels, current()) { index ->
                    select(index)
                    appearanceUpdates.forEach { it() }
                }
            }
            bindRowPress(row)
            appearanceCard.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val directions = LayoutMode.ScreenDirection.entries
        addChoice("屏幕方向", MenuIconView.DIRECTION, directions.map { it.label }, { directions.indexOf(LayoutMode.screenDirection(context)) }) { i ->
            (context as? Activity)?.let { LayoutMode.setScreenDirection(it, directions[i]) }
        }
        val qualities = PreferredVideoQuality.choices
        addChoice("默认画质", MenuIconView.QUALITY, qualities.map(PreferredVideoQuality::label), { qualities.indexOf(PreferredVideoQuality.setting(context)) }) { i ->
            PreferredVideoQuality.set(context, qualities[i])
        }
        return appearanceCard
    }

    private fun showAppearanceChoices(anchor: View, title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        appearancePopup?.dismiss()
        val c = Ds.colors
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            background = Ds.card(context, c.surface, c.line, Ds.Radius.control)
            addView(column)
        }
        val popup = android.widget.PopupWindow(scroll, dp(192), LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        labels.forEachIndexed { index, label ->
            column.addView(TextView(context).apply {
                text = label
                textSize = 13f
                typeface = Ds.body(index == selected)
                setTextColor(if (index == selected) c.accent else c.text)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minimumHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                isSelected = index == selected
                isFocusable = true
                contentDescription = "$title：$label"
                if (isSelected) background = Ds.card(context, c.accentSoft, null, Ds.Radius.chip, 0)
                setOnClickListener { popup.dismiss(); onPick(index) }
                Ds.bindPressFeedback(this, 0.97f)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val visible = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        popup.width = minOf(dp(192), (visible.width() - dp(24)).coerceAtLeast(dp(120)))
        column.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        popup.height = minOf(column.measuredHeight, (visible.height() * .55f).toInt())
        popup.setOnDismissListener { appearancePopup = null }
        appearancePopup = popup
        popup.showAsDropDown(anchor, anchor.width - popup.width, dp(6))
        scroll.post { scroll.scrollTo(0, (column.getChildAt(selected).top - popup.height / 2).coerceAtLeast(0)) }
    }

    private fun buildLoginPage() {
        loginPage.orientation = VERTICAL
        loginPage.setPadding(dp(Ds.Space.lg), dp(Ds.Space.xl), dp(Ds.Space.lg), dp(96))

        val heroCard = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(Ds.Space.xl), dp(Ds.Space.xl), dp(Ds.Space.xl), dp(Ds.Space.xl))
        }
        heroCard.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_logo_round)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "YuiBili"
            Ds.clipRounded(this, Ds.Radius.card)
        }, LayoutParams(dp(56), dp(56)))

        heroCard.addView(TextView(context).apply {
            text = "YuiBili"
            textSize = 22f
            typeface = Ds.display(bold = true)
            letterSpacing = -0.02f
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ds.Space.md)
        })

        heroCard.addView(TextView(context).apply {
            text = "哔哩哔哩 App 扫码登录"
            textSize = 12.5f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ds.Space.xs)
        })

        val qrCard = FrameLayout(context).apply {
            background = Ds.card(context, Color.WHITE, Ds.colors.lineSoft, Ds.Radius.control)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        qrImage.apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.WHITE)
            contentDescription = "登录二维码"
            isLongClickable = true
            setOnLongClickListener {
                saveQrToGallery()
                true
            }
        }
        qrCard.addView(qrImage, FrameLayout.LayoutParams(dp(188), dp(188)))
        heroCard.addView(qrCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ds.Space.lg)
        })

        qrStatus.apply {
            text = "正在生成二维码…"
            textSize = 12.5f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
        }
        heroCard.addView(qrStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ds.Space.md)
        })

        val qrActions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        qrActions.addView(TextView(context).apply {
            text = "保存二维码"
            textSize = 13.5f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(context, COLOR_ROSE)
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(10), dp(20), dp(10))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener { saveQrToGallery() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        qrActions.addView(TextView(context).apply {
            text = "刷新"
            textSize = 13.5f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
            background = Ds.pill(context, COLOR_SURFACE, Ds.colors.line)
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(10), dp(20), dp(10))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener { startQrLogin() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(Ds.Space.md)
        })
        heroCard.addView(qrActions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ds.Space.lg)
        })

        val isTabletWide = LayoutMode.isWideScreen(context)
        val guestMenu = buildMenuCard(includeFavorites = false)
        val guestAppearance = buildAppearanceCard()
        if (isTabletWide) {
            // 平板宽屏游客态：直接占满右侧工作台（绝不用固定 960dp 硬写宽度，防止超出父容器左裁切）
            loginPage.setPadding(dp(24), dp(22), dp(24), dp(32))
            val split = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
            }
            val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
            rightCol.addView(sectionLabel("外观"), sectionParams(0))
            rightCol.addView(guestAppearance, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })
            rightCol.addView(sectionLabel("更多"), sectionParams(Ds.Space.lg))
            rightCol.addView(guestMenu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })
            split.addView(heroCard, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.05f))
            split.addView(rightCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(20)
            })
            loginPage.addView(split, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            loginPage.addView(heroCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            loginPage.addView(sectionLabel("外观"), sectionParams(Ds.Space.lg))
            loginPage.addView(guestAppearance, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })
            loginPage.addView(sectionLabel("更多"), sectionParams(Ds.Space.lg))
            loginPage.addView(guestMenu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })
        }
    }

    private fun buildProfilePage() {
        profilePage.orientation = VERTICAL
        profilePage.setPadding(dp(Ds.Space.lg), dp(Ds.Space.lg), dp(Ds.Space.lg), dp(96))

        // ── 个人信息卡片 ──────────────────────────────────────────
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(Ds.Space.lg), dp(Ds.Space.lg), dp(Ds.Space.lg), dp(Ds.Space.md))
        }
        val headRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val openOwnSpace = View.OnClickListener {
            val info = lastBoundProfile ?: BiliSessionStore.cachedProfile()
            val uid = info?.uid ?: 0L
            if (uid > 0L) {
                SpaceActivity.open(
                    context,
                    uid,
                    info?.uname.orEmpty(),
                    info?.face.orEmpty()
                )
            }
        }
        val identityZone = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "打开我的空间"
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener(openOwnSpace)
        }
        identityZone.addView(cardAvatar, LayoutParams(dp(60), dp(60)))

        val nameBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        cardName.apply {
            textSize = 19f
            typeface = Ds.display(bold = true)
            letterSpacing = -0.01f
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nameBox.addView(cardName, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val metaRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        cardBadges.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(cardBadges, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        cardUid.apply {
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        metaRow.addView(cardUid, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        nameBox.addView(metaRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        identityZone.addView(nameBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headRow.addView(identityZone, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val logoutBtn = FrameLayout(context).apply {
            background = Ds.circle(COLOR_SURFACE, Ds.colors.lineSoft, Ds.hairline(context))
            isClickable = true
            isFocusable = true
            contentDescription = "退出登录"
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { showLogoutConfirm() }
            addView(
                LogoutIconView(context),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
        }
        headRow.addView(logoutBtn, LayoutParams(dp(38), dp(38)))
        card.addView(headRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 统计数据条
        card.addView(
            View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context)).apply {
                topMargin = dp(16)
                bottomMargin = dp(12)
            }
        )
        val statRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        statRow.addView(buildStat("关注", cardFollowing), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        statRow.addView(
            View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LayoutParams(Ds.hairline(context), dp(24))
        )
        statRow.addView(buildStat("粉丝", cardFollower), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        statRow.addView(
            View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LayoutParams(Ds.hairline(context), dp(24))
        )
        statRow.addView(buildStat("硬币", cardCoins), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(statRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        profilePage.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 外观卡片（夜间模式 + 外观适配下拉切换） & 内容与工具卡片 ──
        val appearanceCard = buildAppearanceCard()
        val menuCard = buildMenuCard(includeFavorites = true)

        if (LayoutMode.isWideScreen(context)) {
            // 平板宽屏双栏仪表盘：直接 MATCH_PARENT 占满右侧主舞台，彻底修复左侧头像/图标被裁切的问题
            profilePage.removeAllViews()
            profilePage.setPadding(dp(24), dp(22), dp(24), dp(32))
            val split = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
            }
            val leftCol = LinearLayout(context).apply { orientation = VERTICAL }
            leftCol.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            leftCol.addView(sectionLabel("外观"), sectionParams(Ds.Space.lg))
            leftCol.addView(appearanceCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })

            val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
            rightCol.addView(sectionLabel("内容与工具"), sectionParams(0))
            rightCol.addView(menuCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })

            split.addView(leftCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.05f))
            split.addView(rightCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(20)
            })
            profilePage.addView(split, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            profilePage.addView(sectionLabel("外观"), sectionParams())
            profilePage.addView(appearanceCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })

            profilePage.addView(sectionLabel("内容与工具"), sectionParams())
            profilePage.addView(menuCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ds.Space.sm)
            })
        }
    }

    private fun buildMenuCard(includeFavorites: Boolean): LinearLayout {
        val menuCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(0, dp(Ds.Space.xs), 0, dp(Ds.Space.xs))
        }
        fun addMenuDivider() {
            menuCard.addView(
                View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context)).apply {
                    leftMargin = dp(Ds.Space.lg + 34 + Ds.Space.md)
                    rightMargin = dp(Ds.Space.lg)
                }
            )
        }
        fun menuRow(iconKind: Int, label: String, onClick: () -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ds.Space.lg), 0, dp(Ds.Space.lg), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                addView(
                    iconChip(iconKind),
                    LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(Ds.Space.md) }
                )
                addView(TextView(context).apply {
                    text = label
                    textSize = 15f
                    typeface = Ds.body(true)
                    setTextColor(COLOR_INK)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    Ds.chevronRightIcon(context, Ds.colors.subtle, 1.7f),
                    LayoutParams(dp(14), dp(14))
                )
            }
            bindRowPress(row)
            menuCard.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        }

        menuRow(MenuIconView.HISTORY, "历史记录") {
            openSubPage(ProfileSubActivity.MODE_HISTORY)
        }
        if (includeFavorites) {
            addMenuDivider()
            menuRow(MenuIconView.FAVORITE, "我的收藏") {
                openSubPage(ProfileSubActivity.MODE_FAVORITES)
            }
        }
        addMenuDivider()
        menuRow(MenuIconView.DOWNLOADS, "下载管理") {
            DownloadManagerActivity.open(context)
        }
        addMenuDivider()
        menuRow(MenuIconView.SPONSOR, "赞助开发") {
            openSubPage(ProfileSubActivity.MODE_SPONSOR)
        }
        addMenuDivider()
        menuRow(MenuIconView.ABOUT, "关于软件") {
            openSubPage(ProfileSubActivity.MODE_ABOUT)
        }
        return menuCard
    }

    private fun openSubPage(mode: String) {
        context.startActivity(
            Intent(context, ProfileSubActivity::class.java)
                .putExtra(ProfileSubActivity.EXTRA_MODE, mode)
        )
        (context as? Activity)?.let {
            @Suppress("DEPRECATION")
            it.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
        }
    }

    private fun iconChip(kind: Int): FrameLayout = FrameLayout(context).apply {
        background = Ds.card(context, COLOR_ROSE_SOFT, null, Ds.Radius.chip, 0)
        addView(
            MenuIconView(context, kind, COLOR_ROSE),
            FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        )
    }

    private fun bindRowPress(row: View) {
        row.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> DsSpring.pressLift(view, true, 0.982f)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> DsSpring.pressLift(view, false, 0.982f)
            }
            false
        }
    }

    /** 保留夜间模式的圆形揭示/收拢动画切换效果。 */
    private fun toggleDarkMode(switchView: PillSwitch, anchor: View) {
        val next = !Theme.isDark
        switchView.toggle()
        val activity = context as? Activity
        if (activity != null) {
            try {
                val decor = activity.window.decorView
                val content = decor.findViewById<View>(android.R.id.content) ?: decor
                val switchLoc = IntArray(2)
                val contentLoc = IntArray(2)
                anchor.getLocationOnScreen(switchLoc)
                content.getLocationOnScreen(contentLoc)
                val cx = switchLoc[0] - contentLoc[0] + anchor.width / 2f
                val cy = switchLoc[1] - contentLoc[1] + anchor.height / 2f
                val bmp = Bitmap.createBitmap(
                    content.width.coerceAtLeast(1),
                    content.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                content.draw(Canvas(bmp))
                Theme.prepareCircularTransition(bmp, cx, cy, expand = next)
            } catch (_: Exception) {
                // 截图失败时静默回退到普通 recreate
            }
        }
        Theme.setDark(context, next)
        @Suppress("DEPRECATION")
        activity?.overridePendingTransition(0, 0)
        activity?.recreate()
    }

    /** 首次无缓存时播放错峰入场；已有本地缓存秒出时保持静止避免闪烁。 */
    private fun playEntrance() {
        if (entrancePlayed) return
        entrancePlayed = true
        if (profileBound && BiliSessionStore.isLoggedIn()) return
        val target = if (BiliSessionStore.isLoggedIn()) profilePage else loginPage
        if (target.childCount == 0 || MotionTokens.isReduced(context)) return
        for (index in 0 until target.childCount) {
            DsSpring.enter(target.getChildAt(index), index)
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        typeface = Ds.body(true)
        letterSpacing = 0.12f
        setTextColor(Ds.colors.subtle)
        includeFontPadding = false
        setPadding(dp(4), 0, 0, 0)
    }

    private fun sectionParams(topDp: Int = Ds.Space.lg): LayoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    override fun onDetachedFromWindow() {
        setActive(false)
        super.onDetachedFromWindow()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            refresh(soft = true)
            playEntrance()
        } else {
            appearancePopup?.dismiss()
            qrRequestGeneration++
            stopQrPolling()
            profileGeneration++
            profileInFlight = false
        }
    }

    // ── 刷新状态 ──────────────────────────────────────────────────

    private fun refresh(soft: Boolean = false) {
        if (!active) return
        appearanceUpdates.forEach { it() }
        BiliSessionStore.init(context)
        if (BiliSessionStore.isLoggedIn()) {
            stopQrPolling()
            if (loginPage.visibility == View.VISIBLE) appearancePopup?.dismiss()
            loginPage.visibility = View.GONE
            profilePage.visibility = View.VISIBLE
            loadProfile(soft = soft && profileBound)
        } else {
            profileBound = false
            lastBoundProfile = null
            lastProfileFace = ""
            if (profilePage.visibility == View.VISIBLE) appearancePopup?.dismiss()
            loginPage.visibility = View.VISIBLE
            profilePage.visibility = View.GONE
            if (qrKey.isBlank()) startQrLogin() else startPolling()
        }
    }

    // ── 二维码登录 ────────────────────────────────────────────────

    private fun startQrLogin() {
        if (!active) return
        val gen = ++qrRequestGeneration
        stopQrPolling()
        qrImage.setImageDrawable(null)
        qrBitmap = null
        qrContent = ""
        qrKey = ""
        qrStatus.text = "正在生成二维码…"
        BiliLoginApi.generateQrCode { url, key, error ->
            mainHandler.post {
                if (!active || gen != qrRequestGeneration) return@post
                if (error != null || url.isNullOrBlank() || key.isNullOrBlank()) {
                    qrStatus.text = "二维码生成失败，点击刷新重试"
                    return@post
                }
                qrKey = key
                if (renderQr(url)) {
                    qrStatus.text = "等待扫码"
                    startPolling()
                }
            }
        }
    }

    private fun renderQr(content: String): Boolean {
        return try {
            qrContent = content
            val size = dp(196)
            val bitmap = buildQrBitmap(content, size, marginModules = 2)
            qrBitmap = bitmap
            qrImage.setImageBitmap(bitmap)
            true
        } catch (_: Exception) {
            qrContent = ""
            qrBitmap = null
            qrStatus.text = "二维码生成失败，点击刷新重试"
            false
        }
    }

    private fun buildQrBitmap(content: String, sizePx: Int, marginModules: Int = 2): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to marginModules, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private fun saveQrToGallery() {
        val bitmap = try {
            if (qrContent.isNotBlank()) {
                buildQrBitmap(qrContent, sizePx = 800, marginModules = 3)
            } else {
                qrBitmap ?: (qrImage.drawable as? BitmapDrawable)?.bitmap
            }
        } catch (_: Exception) {
            qrBitmap ?: (qrImage.drawable as? BitmapDrawable)?.bitmap
        }
        if (bitmap == null) {
            Toast.makeText(context, "二维码还未生成", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "YuiBili登录二维码_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YuiBili")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    Toast.makeText(context, "已保存到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                val act = context as? Activity
                if (act != null) {
                    act.requestPermissions(
                        arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        0x51A1
                    )
                    Toast.makeText(context, "请允许存储权限后再次点击保存", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请在系统设置中授予存储权限后重试", Toast.LENGTH_SHORT).show()
                }
                return
            }
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "YuiBili"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                context.sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file))
                )
                Toast.makeText(context, "已保存到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPolling() {
        if (!active || qrPolling || qrKey.isBlank()) return
        qrPolling = true
        pollRunnable.run()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!active || !qrPolling || qrKey.isBlank()) return
            val generation = qrPollGeneration
            BiliLoginApi.pollQrCode(qrKey) { state, cookie ->
                mainHandler.post {
                    if (!active || !qrPolling || generation != qrPollGeneration) return@post
                    when (state) {
                        BiliLoginApi.QR_CONFIRMED -> {
                            stopQrPolling()
                            if (!cookie.isNullOrBlank() && BiliSessionStore.saveCookie(context, cookie)) {
                                Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                                resetLoginPage()
                                refresh()
                            } else {
                                qrStatus.text = "登录凭证获取失败，请刷新重试"
                            }
                        }
                        BiliLoginApi.QR_SCANNED -> {
                            qrStatus.text = "已扫码，请在手机上确认"
                            if (qrPolling) mainHandler.postDelayed(this, 1200)
                        }
                        BiliLoginApi.QR_EXPIRED -> {
                            stopQrPolling()
                            qrKey = ""
                            qrStatus.text = "二维码已过期，点击刷新"
                        }
                        BiliLoginApi.QR_FAILED -> {
                            if (qrPolling) mainHandler.postDelayed(this, 2000)
                        }
                        else -> {
                            if (qrPolling) mainHandler.postDelayed(this, 2000)
                        }
                    }
                }
            }
        }
    }

    private fun stopQrPolling() {
        qrPollGeneration++
        qrPolling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    // ── 用户信息卡片 ──────────────────────────────────────────────

    private var profileGeneration = 0
    private var profileBound = false
    private var lastProfileFace = ""
    private var lastBoundProfile: BiliLoginApi.UserInfo? = null
    private var profileInFlight = false
    private var lastProfileFetchAt = 0L

    init {
        if (BiliSessionStore.isLoggedIn()) {
            loginPage.visibility = View.GONE
            profilePage.visibility = View.VISIBLE
            BiliSessionStore.cachedProfile()?.let {
                applyProfile(it, allowAvatarReload = true)
                profileBound = true
                lastProfileFetchAt = BiliSessionStore.cachedProfileAt()
            }
        }
    }

    fun syncCachedProfile(info: BiliLoginApi.UserInfo) {
        if (!BiliSessionStore.isLoggedIn()) return
        if (loginPage.visibility == View.VISIBLE) appearancePopup?.dismiss()
        appearanceUpdates.forEach { it() }
        loginPage.visibility = View.GONE
        profilePage.visibility = View.VISIBLE
        lastProfileFetchAt = System.currentTimeMillis()
        applyProfile(info, allowAvatarReload = true)
        profileBound = true
    }

    private fun loadProfile(soft: Boolean = false) {
        val now = System.currentTimeMillis()
        if (soft && profileBound && now - lastProfileFetchAt < 60_000L) return
        if (profileInFlight && soft) return

        var quiet = soft
        val cached = BiliSessionStore.cachedProfile()
        if (!profileBound && cached != null) {
            applyProfile(cached, allowAvatarReload = true)
            profileBound = true
            lastProfileFetchAt = BiliSessionStore.cachedProfileAt().takeIf { it > 0L } ?: now
            quiet = true
            if (now - lastProfileFetchAt < 60_000L) {
                return
            }
        }

        val generation = ++profileGeneration
        if (!quiet && !profileBound) {
            cardName.text = "加载中…"
            cardUid.text = ""
            cardBadges.removeAllViews()
            cardFollowing.text = "—"
            cardFollower.text = "—"
            cardCoins.text = "—"
        }
        profileInFlight = true
        val cookie = BiliSessionStore.cookie()
        BiliLoginApi.fetchProfile(
            cookie = cookie,
            onBaseReady = { baseInfo ->
                mainHandler.post {
                    if (!active || generation != profileGeneration || cookie != BiliSessionStore.cookie()) return@post
                    // 基本资料（头像/昵称/VIP/等级）就绪即先渲染，不干等统计接口
                    if (!profileBound) {
                        applyProfile(baseInfo, allowAvatarReload = true)
                        profileBound = true
                    }
                }
            }
        ) { info, error ->
            mainHandler.post {
                if (!active || generation != profileGeneration) return@post
                profileInFlight = false
                if (cookie != BiliSessionStore.cookie()) return@post
                lastProfileFetchAt = System.currentTimeMillis()
                if (info == null) {
                    if (!profileBound) {
                        cardBadges.removeAllViews()
                        cardName.text = "加载失败"
                        cardUid.text = error ?: "请尝试重新登录"
                    }
                    return@post
                }
                BiliSessionStore.cacheProfile(info)
                applyProfile(info, allowAvatarReload = true)
                profileBound = true
            }
        }
    }

    private fun loadAvatarWithRetry(faceUrl: String, attemptsLeft: Int) {
        CoverLoader.load(
            cardAvatar,
            faceUrl,
            cropThumb = false,
            thumbEdge = 128,
            priority = CoverLoader.PRIORITY_HIGH,
            onFailure = {
                if (attemptsLeft > 0 && lastProfileFace == faceUrl) {
                    mainHandler.postDelayed({
                        if (lastProfileFace == faceUrl) {
                            loadAvatarWithRetry(faceUrl, attemptsLeft - 1)
                        }
                    }, 900L)
                }
            }
        )
    }

    private fun applyProfile(info: BiliLoginApi.UserInfo, allowAvatarReload: Boolean) {
        BiliSessionStore.setVip(info.vipStatus, info.vipType)
        val prev = lastBoundProfile
        if (prev == null || prev.uname != info.uname) {
            cardName.text = info.uname
        }
        if (prev == null || prev.uid != info.uid) {
            cardUid.text = "UID ${info.uid}"
        }
        val faceUrl = if (info.face.isNotBlank()) info.face + "@128w_128h.webp" else ""
        if ((allowAvatarReload || lastProfileFace.isBlank()) && faceUrl.isNotBlank() && faceUrl != lastProfileFace) {
            lastProfileFace = faceUrl
            loadAvatarWithRetry(faceUrl, attemptsLeft = 2)
        }
        if (prev == null || prev.vipStatus != info.vipStatus || prev.vipType != info.vipType || prev.level != info.level) {
            cardBadges.removeAllViews()
            if (info.vipStatus == 1 && info.vipType >= 1) {
                cardBadges.addView(badge("大会员", COLOR_ROSE, COLOR_ROSE_SOFT))
            }
            cardBadges.addView(badge("LV${info.level}", COLOR_MUTED, COLOR_SOFT_FILL).apply {
                if (cardBadges.childCount > 0) {
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(6)
                    }
                }
            })
        }
        if (prev == null || prev.following != info.following) {
            cardFollowing.text = formatCount(info.following)
        }
        if (prev == null || prev.follower != info.follower) {
            cardFollower.text = formatCount(info.follower)
        }
        val formattedCoins = formatCoins(info.coins)
        val prevCoins = prev?.let { formatCoins(it.coins) }
        if (prev == null || prevCoins != formattedCoins) {
            cardCoins.text = formattedCoins
        }
        lastBoundProfile = info
    }

    private fun formatCoins(coins: Double): String = if (coins > 0) {
        if (coins == coins.toLong().toDouble()) "${coins.toLong()}" else String.format(Locale.US, "%.1f", coins)
    } else "0"

    private fun badge(text: String, textColor: Int, fill: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 10f
        typeface = Ds.body(true)
        setTextColor(textColor)
        background = rounded(fill, dp(6))
        setPadding(dp(7), dp(2), dp(7), dp(2))
        includeFontPadding = false
    }

    private fun buildStat(label: String, valueView: TextView): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            valueView.apply {
                textSize = 16f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
                includeFontPadding = false
            }
            addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                typeface = Ds.body()
                setTextColor(COLOR_MUTED)
                includeFontPadding = false
                setPadding(0, dp(4), 0, 0)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

    private fun formatCount(count: Int): String = when {
        count >= 10000 -> String.format(Locale.CHINA, "%.1f万", count / 10000.0)
        count >= 1000 -> String.format(Locale.CHINA, "%.1f千", count / 1000.0)
        else -> count.toString()
    }

    private fun showLogoutConfirm() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(TextView(context).apply {
            text = "退出登录"
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(context).apply {
            text = "将清除本机登录凭证，确定退出？"
            textSize = 13.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(10), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }
        buttons.addView(TextView(context).apply {
            text = "取消"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            background = Ds.pill(context, COLOR_SURFACE, Ds.colors.lineSoft)
            setPadding(dp(20), dp(9), dp(20), dp(9))
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener { dialog.dismiss() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        buttons.addView(TextView(context).apply {
            text = "退出"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(context, COLOR_ROSE)
            setPadding(dp(20), dp(9), dp(20), dp(9))
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                dialog.dismiss()
                BiliSessionStore.clear(context)
                resetLoginPage()
                refresh()
            }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        panel.addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        dialog.setContentView(panel)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, context, maxTabletDp = 420)
    }

    private fun resetLoginPage() {
        qrRequestGeneration++
        profileGeneration++
        profileInFlight = false
        profileBound = false
        lastBoundProfile = null
        lastProfileFace = ""
        cardAvatar.setImageDrawable(null)
        stopQrPolling()
        qrImage.setImageDrawable(null)
        qrBitmap = null
        qrContent = ""
        qrKey = ""
    }

    private class RoundAvatar(context: Context) : ImageView(context) {
        private val clipPath = Path()
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val radius = width.coerceAtMost(height) / 2f
            placeholderPaint.color = COLOR_COVER
            canvas.drawCircle(width / 2f, height / 2f, radius, placeholderPaint)
            val drawable = drawable ?: return
            clipPath.reset()
            clipPath.addCircle(width / 2f, height / 2f, radius, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(clipPath)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    private class MenuIconView(
        context: Context,
        private val kind: Int,
        private val iconColor: Int = COLOR_ROSE
    ) : View(context) {
        companion object {
            const val HISTORY = 0
            const val FAVORITE = 1
            const val SPONSOR = 2
            const val ABOUT = 4
            const val DOWNLOADS = 5
            const val MOON = 6
            const val DIRECTION = 7
            const val QUALITY = 8
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.FILL
        }
        private val density: Float = resources.displayMetrics.density
        private fun dpf(v: Float) = v * density

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            paint.strokeWidth = dpf(1.75f)
            when (kind) {
                HISTORY -> {
                    val r = dpf(7.5f)
                    canvas.drawCircle(cx, cy, r, paint)
                    canvas.drawLine(cx, cy, cx, cy - dpf(4.2f), paint)
                    canvas.drawLine(cx, cy, cx + dpf(3.4f), cy + dpf(1.4f), paint)
                }
                FAVORITE -> {
                    val outer = dpf(7.8f)
                    val inner = outer * 0.43f
                    val path = Path()
                    for (i in 0 until 5) {
                        val aOut = Math.toRadians((-90 + i * 72).toDouble())
                        val aIn = Math.toRadians((-90 + i * 72 + 36).toDouble())
                        val ox = cx + (outer * Math.cos(aOut)).toFloat()
                        val oy = cy + (outer * Math.sin(aOut)).toFloat()
                        val ix = cx + (inner * Math.cos(aIn)).toFloat()
                        val iy = cy + (inner * Math.sin(aIn)).toFloat()
                        if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
                        path.lineTo(ix, iy)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                SPONSOR -> {
                    val path = Path()
                    val top = cy - dpf(3f)
                    path.moveTo(cx, cy + dpf(6.2f))
                    path.cubicTo(
                        cx - dpf(9.4f), cy + dpf(0.4f),
                        cx - dpf(8.8f), top - dpf(3.2f),
                        cx, top + dpf(1.4f)
                    )
                    path.cubicTo(
                        cx + dpf(8.8f), top - dpf(3.2f),
                        cx + dpf(9.4f), cy + dpf(0.4f),
                        cx, cy + dpf(6.2f)
                    )
                    path.close()
                    canvas.drawPath(path, paint)
                }
                ABOUT -> {
                    canvas.drawCircle(cx, cy, dpf(7.6f), paint)
                    canvas.drawCircle(cx, cy - dpf(3.2f), dpf(1.1f), fill)
                    canvas.drawLine(cx, cy - dpf(0.8f), cx, cy + dpf(3.8f), paint)
                }
                DOWNLOADS -> {
                    canvas.drawLine(cx, cy - dpf(6.8f), cx, cy + dpf(1.2f), paint)
                    val arrow = dpf(3.5f)
                    canvas.drawLine(cx - arrow, cy + dpf(1.2f) - arrow, cx, cy + dpf(1.2f), paint)
                    canvas.drawLine(cx + arrow, cy + dpf(1.2f) - arrow, cx, cy + dpf(1.2f), paint)
                    canvas.drawLine(cx - dpf(7.0f), cy + dpf(3.0f), cx - dpf(7.0f), cy + dpf(6.8f), paint)
                    canvas.drawLine(cx + dpf(7.0f), cy + dpf(3.0f), cx + dpf(7.0f), cy + dpf(6.8f), paint)
                    canvas.drawLine(cx - dpf(7.0f), cy + dpf(6.8f), cx + dpf(7.0f), cy + dpf(6.8f), paint)
                }
                DIRECTION -> {
                    canvas.drawRoundRect(cx - dpf(3.5f), cy - dpf(6.5f), cx + dpf(3.5f), cy + dpf(6.5f), dpf(1.2f), dpf(1.2f), paint)
                    canvas.drawLine(cx + dpf(6f), cy - dpf(3f), cx + dpf(6f), cy + dpf(3f), paint)
                    canvas.drawLine(cx + dpf(6f), cy + dpf(3f), cx + dpf(8f), cy + dpf(1f), paint)
                    canvas.drawLine(cx - dpf(6f), cy + dpf(3f), cx - dpf(6f), cy - dpf(3f), paint)
                    canvas.drawLine(cx - dpf(6f), cy - dpf(3f), cx - dpf(8f), cy - dpf(1f), paint)
                }
                QUALITY -> {
                    canvas.drawRoundRect(cx - dpf(8f), cy - dpf(5.7f), cx + dpf(8f), cy + dpf(5.7f), dpf(1.7f), dpf(1.7f), paint)
                    fill.textSize = dpf(6.5f)
                    fill.typeface = Ds.body(true)
                    fill.textAlign = Paint.Align.CENTER
                    canvas.drawText("HD", cx, cy - (fill.ascent() + fill.descent()) / 2f, fill)
                }
                MOON -> {
                    val path = Path()
                    val r = dpf(7.2f)
                    path.addArc(cx - r, cy - r, cx + r, cy + r, 40f, 240f)
                    path.quadTo(cx - dpf(1.5f), cy - dpf(0.5f), cx + r * 0.77f, cy + r * 0.64f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    private class LogoutIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val density: Float = resources.displayMetrics.density
        private val dpf: (Float) -> Float = { it * density }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            paint.strokeWidth = dpf(1.75f)
            val left = cx - dpf(7f)
            val top = cy - dpf(6.5f)
            val right = cx + dpf(1f)
            val bottom = cy + dpf(6.5f)
            val r = dpf(2.2f)
            val door = Path().apply {
                moveTo(right, top + dpf(1.8f))
                lineTo(right, top + r)
                quadTo(right, top, right - r, top)
                lineTo(left + r, top)
                quadTo(left, top, left, top + r)
                lineTo(left, bottom - r)
                quadTo(left, bottom, left + r, bottom)
                lineTo(right - r, bottom)
                quadTo(right, bottom, right, bottom - r)
                lineTo(right, bottom - dpf(1.8f))
            }
            canvas.drawPath(door, paint)
            val startX = cx - dpf(1f)
            val endX = cx + dpf(8f)
            canvas.drawLine(startX, cy, endX, cy, paint)
            canvas.drawLine(endX - dpf(3.2f), cy - dpf(3.2f), endX, cy, paint)
            canvas.drawLine(endX - dpf(3.2f), cy + dpf(3.2f), endX, cy, paint)
        }
    }

    private class PillSwitch(context: Context, initial: Boolean) : View(context) {
        var isOn: Boolean = initial
            private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density: Float = resources.displayMetrics.density
        private val dpf: (Float) -> Float = { it * density }
        private var progress: Float = if (initial) 1f else 0f
        private var animator: ValueAnimator? = null

        init {
            isClickable = false
            isFocusable = false
        }

        fun toggle() {
            val target = !isOn
            isOn = target
            animator?.cancel()
            if (MotionTokens.isReduced(context)) {
                progress = if (target) 1f else 0f
                invalidate()
                return
            }
            val start = progress
            animator = ValueAnimator.ofFloat(start, if (target) 1f else 0f).apply {
                duration = 220L
                interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val c = Ds.colors
            val r = h / 2f
            paint.style = Paint.Style.FILL
            paint.color = mixColor(c.line, c.accent, progress)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

            val pad = dpf(3f)
            val knobSize = h - pad * 2f
            val knobX = pad + (w - knobSize - pad * 2f) * progress
            paint.color = Color.WHITE
            canvas.drawCircle(knobX + knobSize / 2f, h / 2f, knobSize / 2f, paint)
        }

        private fun mixColor(from: Int, to: Int, t: Float): Int {
            val fr = (from shr 16) and 0xFF
            val fg = (from shr 8) and 0xFF
            val fb = from and 0xFF
            val tr = (to shr 16) and 0xFF
            val tg = (to shr 8) and 0xFF
            val tb = to and 0xFF
            return Color.rgb(
                (fr + (tr - fr) * t).toInt(),
                (fg + (tg - fg) * t).toInt(),
                (fb + (tb - fb) * t).toInt()
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
