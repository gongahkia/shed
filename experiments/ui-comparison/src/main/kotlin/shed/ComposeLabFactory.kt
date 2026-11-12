package shed

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Presentation only: both implementations receive the same immutable state and real operations. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class, ExperimentalMaterialApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)
class ComposeLabFactory : LabFactory {
    private fun ui(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }
    private fun view(model: LabModel, slot: String, content: @Composable (LabModel.State, ComposePanel, MutableMap<String,Rectangle>, FocusRequester) -> Unit): LabView {
        val panel = ComposePanel()
        val targets = ConcurrentHashMap<String,Rectangle>()
        var focus: (() -> Unit)? = null
        var pendingFocus = false
        panel.setContent {
            var state by remember { mutableStateOf(model.state()) }
            val requester = remember { FocusRequester() }
            DisposableEffect(model) {
                val subscription = model.listen { state = it }
                focus = { requester.requestFocus() }
                onDispose { subscription.close(); focus = null }
            }
            LaunchedEffect(Unit) { if(slot=="settings" && pendingFocus) { requester.requestFocus(); pendingFocus=false } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false, LocalDensity provides Density(density.density * state.style().zoom().toFloat(), density.fontScale * state.style().fontSize()/13f)) {
                val family = when(state.style().fontFamily()) {
                    "Dialog", "SansSerif" -> FontFamily.SansSerif
                    "Monospaced", "DialogInput" -> FontFamily.Monospace
                    "Serif" -> FontFamily.Serif
                    else -> FontFamily(state.style().fontFamily())
                }
                MaterialTheme(colors = darkColors(
                    primary=Color(state.style().accent()), secondary=Color(state.style().field()), background=Color(state.style().surface()),
                    surface=Color(state.style().raised()), onSurface=Color(state.style().foreground()),
                    onBackground=Color(state.style().foreground())), typography=Typography(defaultFontFamily=family,
                    body1=TextStyle(fontFamily=family,fontSize=state.style().fontSize().sp))) {
                    Box(Modifier.fillMaxSize().background(Color(if(slot=="navigation")state.style().raised() else state.style().surface()))
                        .drawWithContent { drawContent(); model.painted(slot) }
                        .onPreviewKeyEvent { event ->
                            if(event.type==KeyEventType.KeyDown && event.key==Key.Escape) {ui{model.dismiss()};true} else false
                        }) { content(state,panel,targets,requester) }
                }
            }
        }
        return object : LabView {
            override fun component(): JComponent = panel
            override fun target(id: String): Rectangle? = targets[id]?.let {
                val origin=panel.locationOnScreen
                Rectangle(origin.x+it.x,origin.y+it.y,it.width,it.height)
            }
            override fun focusSearch() { ui { panel.requestFocusInWindow(); pendingFocus=true;focus?.invoke() } }
            override fun close() { panel.dispose() }
        }
    }
    private fun Modifier.target(panel: ComposePanel, targets: MutableMap<String,Rectangle>, id: String) = onGloballyPositioned {
        val rect=it.boundsInRoot();val scale=panel.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        targets[id]=Rectangle((rect.left/scale).toInt(),(rect.top/scale).toInt(),(rect.width/scale).toInt(),(rect.height/scale).toInt())
    }
    @Composable private fun Action(text: String, panel: ComposePanel, targets: MutableMap<String,Rectangle>, id: String,
                                  modifier: Modifier=Modifier, selected: Boolean=false, action: () -> Unit) {
        TextButton(onClick={ui(action)},modifier=modifier.height(40.dp).target(panel,targets,id)
            .semantics { role=Role.Button; contentDescription=text; this.selected=selected },
            contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)) {
            Text(text,color=MaterialTheme.colors.onSurface,fontSize=13.sp,letterSpacing=0.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    override fun navigation(model: LabModel): LabView = view(model,"navigation") { state,panel,targets,_ ->
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp,vertical=10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("SHED  /  Workbench",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Color(state.style().foreground()),modifier=Modifier.padding(top=10.dp))
                Action("Appearance",panel,targets,"appearance") {model.show()}
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                state.documents().forEachIndexed { index,doc ->
                    Column {
                        Action((if(doc.dirty())"● " else "")+doc.name(),panel,targets,"doc.$index",Modifier.width(180.dp),doc.active()){model.activate(doc.id())}
                        Box(Modifier.width(180.dp).height(2.dp).background(Color(if(doc.active())state.style().accent() else state.style().raised())))
                    }
                }
            }
            Text(state.status(),color=Color(state.style().muted()),fontSize=12.sp)
        }
    }
    override fun settings(model: LabModel): LabView = view(model,"settings") { state,panel,targets,requester ->
        Column(Modifier.fillMaxSize().padding(20.dp)) {
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Appearance",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Color(state.style().foreground()))
            Text("Make space for your work.",fontSize=12.sp,color=Color(state.style().muted()))
            FlowRow(Modifier.fillMaxWidth()) {
                Action("Dark",panel,targets,"dark"){model.update("theme","shed-dark")}
                Action("Light",panel,targets,"light"){model.update("theme","shed-light")}
                Action(if(state.detached())"Dock" else "Detach",panel,targets,"detach"){model.detach()}
                Action("Close",panel,targets,"dismiss"){model.dismiss()}
            }
            Text("Search appearance settings",fontSize=12.sp,color=Color(state.style().muted()))
            Field(state.query(),{model.query(it)},"Search appearance settings",panel,targets,"query",
                Modifier.fillMaxWidth().focusRequester(requester))
            Spacer(Modifier.height(12.dp))
                if(state.settings().isEmpty())Text("No appearance settings match.",color=Color(state.style().muted()),fontSize=13.sp)
                state.settings().forEach { setting ->
                    key(setting.key()) {
                        Column(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
                            Text(LabModel.title(setting.key()),fontWeight=FontWeight.Bold,fontSize=13.sp,color=Color(state.style().foreground()))
                            var draft by remember(setting.currentValue(),state.feedback()) { mutableStateOf(model.draft(setting)) }
                            Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Field(draft,{draft=it;model.draft(setting.key(),it)},LabModel.title(setting.key()),panel,targets,"value.${setting.key()}",Modifier.weight(1f),{model.update(setting.key(),draft)})
                                Action("Apply",panel,targets,"apply.${setting.key()}",Modifier.width(70.dp)){model.update(setting.key(),draft)}
                            }
                            Text(setting.description(),color=Color(state.style().muted()),fontSize=12.sp,modifier=Modifier.padding(top=6.dp),maxLines=3)
                        }
                        Divider(color=Color(state.style().border()))
                    }
                }
            }
            Text(if(state.settings().isEmpty())"No appearance settings match. Try font or theme." else state.feedback(),color=Color(if(state.error())state.style().accent() else state.style().muted()),fontSize=12.sp,
                modifier=Modifier.padding(top=12.dp).semantics { liveRegion=LiveRegionMode.Polite;contentDescription="Settings result: ${state.feedback()}" })
        }
    }
    @Composable private fun Field(value:String, change:(String)->Unit, label:String, panel:ComposePanel,
                                  targets:MutableMap<String,Rectangle>,id:String,modifier:Modifier,submit:(()->Unit)?=null) {
        var focused by remember { mutableStateOf(false) }
        BasicTextField(value=value,onValueChange=change,singleLine=true,
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={submit?.let{ui(it)}}),
            textStyle=TextStyle(color=MaterialTheme.colors.onSurface,fontSize=13.sp,fontFamily=MaterialTheme.typography.body1.fontFamily),
            modifier=modifier.height(40.dp).target(panel,targets,id).semantics { contentDescription=label }
                .background(MaterialTheme.colors.secondary).border(if(focused)2.dp else 1.dp,MaterialTheme.colors.primary.copy(alpha=if(focused)1f else .35f)).padding(horizontal=8.dp,vertical=10.dp))
    }
}
