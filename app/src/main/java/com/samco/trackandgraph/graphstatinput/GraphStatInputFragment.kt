/* 
* This file is part of Track & Graph
* 
* Track & Graph is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* 
* Track & Graph is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* 
* You should have received a copy of the GNU General Public License
* along with Track & Graph.  If not, see <https://www.gnu.org/licenses/>.
*/
package com.samco.trackandgraph.graphstatinput

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.room.withTransaction
import com.samco.trackandgraph.MainActivity
import com.samco.trackandgraph.NavButtonStyle
import com.samco.trackandgraph.R
import com.samco.trackandgraph.database.*
import com.samco.trackandgraph.database.dto.*
import com.samco.trackandgraph.database.entity.*
import com.samco.trackandgraph.databinding.FragmentGraphStatInputBinding
import com.samco.trackandgraph.graphclassmappings.graphStatTypes
import com.samco.trackandgraph.graphstatinput.configviews.*
import com.samco.trackandgraph.graphstatview.factories.viewdto.IGraphStatViewData
import com.samco.trackandgraph.ui.FeaturePathProvider
import com.samco.trackandgraph.util.hideKeyboard
import kotlinx.coroutines.*
import java.lang.Exception
import kotlin.reflect.full.primaryConstructor


class GraphStatInputFragment : Fragment() {
    private var navController: NavController? = null
    private val args: GraphStatInputFragmentArgs by navArgs()
    private lateinit var binding: FragmentGraphStatInputBinding
    private val viewModel by viewModels<GraphStatInputViewModel>()

    private val updateDemoHandler = Handler()

    private lateinit var currentConfigView: GraphStatConfigView

    private var lastPreviewButtonDownPosY = 0f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        this.navController = container?.findNavController()
        binding = FragmentGraphStatInputBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        viewModel.initViewModel(
            TrackAndGraphDatabase.getInstance(requireContext()),
            args.groupId,
            args.graphStatId
        )
        listenToViewModelState()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setActionBarConfig(
            NavButtonStyle.UP,
            getString(R.string.add_a_graph_or_stat)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.previewOverlay.visibility = View.GONE
        binding.btnPreivew.visibility = View.GONE
        binding.demoGraphStatCardView.hideMenuButton()
    }

    private fun listenToViewModelState() {
        viewModel.state.observe(viewLifecycleOwner, Observer {
            when (it) {
                GraphStatInputState.INITIALIZING -> binding.inputProgressBar.visibility =
                    View.VISIBLE
                GraphStatInputState.WAITING -> {
                    binding.inputProgressBar.visibility = View.INVISIBLE
                    listenToUpdateMode()
                    listenToGraphTypeSpinner()
                    listenToGraphName()
                    listenToFormValid()
                    listenToDemoViewData()
                    listenToAddButton()
                    listenToPreviewButton()
                }
                GraphStatInputState.ADDING -> binding.inputProgressBar.visibility = View.VISIBLE
                else -> navController?.popBackStack()
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun listenToPreviewButton() {
        binding.btnPreivew.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN && binding.btnPreivew.isEnabled) {
                lastPreviewButtonDownPosY = event.y
                v.isPressed = true
                binding.previewOverlay.visibility = View.VISIBLE
                return@setOnTouchListener true
            } else if (event.action == MotionEvent.ACTION_MOVE) {
                val diff = lastPreviewButtonDownPosY - event.y
                binding.previewScrollView.scrollY = (diff * 3f).toInt()
            } else if (event.action == MotionEvent.ACTION_UP) {
                v.isPressed = false
                binding.previewOverlay.visibility = View.GONE
                return@setOnTouchListener true
            }
            return@setOnTouchListener false
        }
    }

    private fun listenToConfigView() {
        currentConfigView.setConfigChangedListener(viewModel::onNewConfigData)
        currentConfigView.setOnScrollListener { binding.scrollView.fullScroll(it) }
        currentConfigView.setOnHideKeyboardListener {
            requireActivity().window.hideKeyboard()
        }
    }

    private fun listenToDemoViewData() {
        viewModel.demoViewData.observe(viewLifecycleOwner, Observer {
            updateDemoView(it)
        })
    }

    private fun listenToUpdateMode() {
        viewModel.updateMode.observe(viewLifecycleOwner, Observer { b ->
            if (b) {
                binding.addBar.addButton.setText(R.string.update)
                binding.graphStatTypeLayout.visibility = View.GONE
            }
        })
    }

    private fun listenToGraphName() {
        binding.graphStatNameInput.setText(viewModel.graphName.value)
        binding.graphStatNameInput.addTextChangedListener { editText ->
            viewModel.setGraphName(editText.toString())
        }
    }

    private fun listenToAddButton() {
        binding.addBar.addButton.setOnClickListener {
            viewModel.createGraphOrStat()
        }
    }

    private fun listenToGraphTypeSpinner() {
        val graphTypes = GraphStatType.values()
        binding.graphTypeSpinner.setSelection(graphTypes.indexOf(viewModel.graphStatType.value))
        viewModel.graphStatType.observe(viewLifecycleOwner, Observer {
            updateViewForSelectedGraphStatType(it)
        })
        binding.graphTypeSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p0: AdapterView<*>?) {}
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, index: Int, p3: Long) {
                    viewModel.setGraphType(graphTypes[index])
                }
            }
    }

    private fun updateViewForSelectedGraphStatType(graphStatType: GraphStatType) {
        binding.configLayout.removeAllViews()
        inflateConfigView(graphStatType)
        currentConfigView.initFromConfigData(viewModel.configData.value, viewModel.featurePathProvider)
        listenToConfigView()
    }

    private fun inflateConfigView(graphStatType: GraphStatType) {
        graphStatTypes[graphStatType]?.configViewClass
            ?.primaryConstructor?.call(requireContext(), null, 0)
            ?.let {
                currentConfigView = it
                binding.configLayout.addView(it)
            }
    }

    private fun listenToFormValid() {
        viewModel.formValid.observe(viewLifecycleOwner, Observer { errorNow ->
            binding.addBar.addButton.isEnabled = errorNow == null
            binding.addBar.errorText.postDelayed({
                val errorThen = viewModel.formValid.value
                val text = errorThen?.let { getString(it.errorMessageId) } ?: ""
                binding.addBar.errorText.text = text
            }, 200)
        })
    }

    private fun updateDemoView(data: IGraphStatViewData?) {
        updateDemoHandler.removeCallbacksAndMessages(null)
        updateDemoHandler.postDelayed(Runnable {
            if (data == null) {
                binding.btnPreivew.visibility = View.GONE
                binding.previewOverlay.visibility = View.GONE
            } else {
                binding.btnPreivew.visibility = View.VISIBLE
                binding.demoGraphStatCardView.graphStatView.initFromGraphStat(data, true)
            }
            if (viewModel.formValid.value != null) {
                binding.demoGraphStatCardView.graphStatView.initError(
                    R.string.graph_stat_view_invalid_setup
                )
            }
        }, 500)
    }

    override fun onDestroyView() {
        binding.demoGraphStatCardView.graphStatView.dispose()
        super.onDestroyView()
        requireActivity().window.hideKeyboard()
    }
}

enum class GraphStatInputState { INITIALIZING, WAITING, ADDING, FINISHED }
class ValidationException(val errorMessageId: Int) : Exception()
class GraphStatInputViewModel : ViewModel() {

    private var database: TrackAndGraphDatabase? = null
    private var dataSource: TrackAndGraphDatabaseDao? = null

    private var updateJob = Job()
    private val ioScope = CoroutineScope(Dispatchers.IO + updateJob)
    private val workScope = CoroutineScope(Dispatchers.Default + updateJob)

    private var graphStatGroupId: Long = -1L
    private var graphStatId: Long? = null
    private var graphStatDisplayIndex: Int? = null

    val graphName: LiveData<String> get() = _graphName
    private val _graphName = MutableLiveData("")

    val graphStatType: LiveData<GraphStatType> get() = _graphStatType
    private val _graphStatType = MutableLiveData(GraphStatType.LINE_GRAPH)

    val updateMode: LiveData<Boolean> get() = _updateMode
    private val _updateMode = MutableLiveData(false)

    val state: LiveData<GraphStatInputState> get() = _state
    private val _state = MutableLiveData(GraphStatInputState.INITIALIZING)

    internal val formValid: LiveData<ValidationException?> get() = _formValid
    private val _formValid = MutableLiveData<ValidationException?>(null)

    private var configException: ValidationException? = null
    private var subConfigException: ValidationException? = null

    val configData: LiveData<Any?> get() = _configData
    private val _configData = MutableLiveData<Any?>(null)
    private val configCache = mutableMapOf<GraphStatType, Any?>()

    val demoViewData: LiveData<IGraphStatViewData?> get() = _demoViewData
    private val _demoViewData = MutableLiveData<IGraphStatViewData?>(null)

    lateinit var featurePathProvider: FeaturePathProvider private set

    fun initViewModel(database: TrackAndGraphDatabase, graphStatGroupId: Long, graphStatId: Long) {
        if (this.database != null) return
        this.database = database
        this.dataSource = database.trackAndGraphDatabaseDao
        this.graphStatGroupId = graphStatGroupId
        _state.value = GraphStatInputState.INITIALIZING
        ioScope.launch {
            val allFeatures = dataSource!!.getAllFeaturesSync()
            val allGroups = dataSource!!.getAllGroupsSync()
            featurePathProvider = FeaturePathProvider(allFeatures, allGroups)
            if (graphStatId != -1L) initFromExistingGraphStat(graphStatId)
            else withContext(Dispatchers.Main) { _state.value = GraphStatInputState.WAITING }
        }
    }

    private suspend fun initFromExistingGraphStat(graphStatId: Long) {
        val graphStat = dataSource!!.tryGetGraphStatById(graphStatId)

        if (graphStat == null) {
            withContext(Dispatchers.Main) { _state.value = GraphStatInputState.WAITING }
            return
        }

        val configData = graphStatTypes[graphStat.type]
            ?.dataSourceAdapter!!.getConfigData(dataSource!!, graphStatId)

        configData?.first?.let {
            withContext(Dispatchers.Main) {
                this@GraphStatInputViewModel._graphName.value = graphStat.name
                this@GraphStatInputViewModel._graphStatType.value = graphStat.type
                this@GraphStatInputViewModel.graphStatId = graphStat.id
                this@GraphStatInputViewModel.graphStatDisplayIndex = graphStat.displayIndex
                this@GraphStatInputViewModel._updateMode.value = true
                this@GraphStatInputViewModel._configData.value = configData.second
            }
        }
        withContext(Dispatchers.Main) { _state.value = GraphStatInputState.WAITING }
    }

    fun setGraphName(name: String) {
        this._graphName.value = name
        validateConfiguration()
        updateDemoData()
    }

    fun setGraphType(type: GraphStatType) {
        this._configData.value = configCache[type]
        this._graphStatType.value = type
        validateConfiguration()
        updateDemoData()
    }

    internal fun onNewConfigData(config: Any?, exception: ValidationException?) {
        workScope.coroutineContext.cancelChildren()
        _configData.value = config
        configCache[_graphStatType.value!!] = config
        subConfigException = exception
        validateConfiguration()
        updateDemoData()
    }

    private fun updateDemoData() {
        if (_formValid.value == null) updateDemoViewData()
        else _demoViewData.value = null
    }

    private fun updateDemoViewData() = workScope.launch {
        if (_configData.value == null) return@launch
        val graphOrStat = constructGraphOrStat()
        withContext(Dispatchers.Main) {
            _demoViewData.value = IGraphStatViewData.loading(graphOrStat)
        }
        val graphStatType = graphStatTypes[graphOrStat.type]
        val demoData = graphStatType?.dataFactory
            ?.getViewData(dataSource!!, graphOrStat, _configData.value!!) {}
        withContext(Dispatchers.Main) { demoData?.let { _demoViewData.value = it } }
    }

    private fun validateConfiguration() {
        configException = when {
            _graphName.value!!.isEmpty() -> ValidationException(R.string.graph_stat_validation_no_name)
            _graphStatType.value == null -> ValidationException(R.string.graph_stat_validation_unknown)
            else -> null
        }
        _formValid.value = configException ?: subConfigException
    }

    fun createGraphOrStat() {
        if (_state.value != GraphStatInputState.WAITING) return
        if (_configData.value == null) return
        _state.value = GraphStatInputState.ADDING
        ioScope.launch {
            database!!.withTransaction {
                val graphStatId = if (_updateMode.value!!) {
                    dataSource!!.updateGraphOrStat(constructGraphOrStat())
                    graphStatId!!
                } else {
                    shiftUpGraphStatViewIndexes()
                    dataSource!!.insertGraphOrStat(constructGraphOrStat())
                }

                graphStatTypes[_graphStatType.value]?.dataSourceAdapter?.writeConfig(
                    dataSource!!, graphStatId, _configData.value!!, _updateMode.value!!
                )
            }
            withContext(Dispatchers.Main) { _state.value = GraphStatInputState.FINISHED }
        }
    }

    private fun shiftUpGraphStatViewIndexes() {
        val newList = dataSource!!.getAllGraphStatsSync().map {
            it.copy(displayIndex = it.displayIndex + 1)
        }
        dataSource!!.updateGraphStats(newList)
    }

    private fun constructGraphOrStat() = GraphOrStat(
        graphStatId ?: 0L, graphStatGroupId, _graphName.value!!, _graphStatType.value!!,
        graphStatDisplayIndex ?: 0
    )

    override fun onCleared() {
        super.onCleared()
        ioScope.cancel()
    }
}
