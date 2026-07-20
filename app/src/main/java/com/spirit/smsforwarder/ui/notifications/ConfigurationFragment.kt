package com.spirit.smsforwarder.ui.notifications

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.spirit.smsforwarder.R
import com.spirit.smsforwarder.databinding.FragmentConfigurationBinding
import kotlinx.coroutines.*

class ConfigurationFragment : Fragment() {

	private var _binding: FragmentConfigurationBinding? = null
	private val binding get() = _binding!!
	private lateinit var configurationViewModel: ConfigurationViewModel
	private var installedApps: List<AppItem> = emptyList()
	private var filteredApps: List<AppItem> = emptyList()
	private lateinit var appAdapter: AppListAdapter
	private var filterJob: Job? = null
	private var currentQuery = ""

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		configurationViewModel = ViewModelProvider(this).get(ConfigurationViewModel::class.java)
		_binding = FragmentConfigurationBinding.inflate(inflater, container, false)

		appAdapter = AppListAdapter(emptyList()) { packageName, isChecked ->
			configurationViewModel.setAppEnabled(packageName, isChecked)
		}
		binding.appListContainer.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
		binding.appListContainer.adapter = appAdapter

		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		observeViewModel()
		setupSearchAndSort()
		loadApps()
	}

	private fun setupSearchAndSort() {
		val sortOptions = arrayOf(getString(R.string.enabled_first_search), getString(R.string.alphabetical_search), getString(R.string.alphabetical_reverse))
		binding.sortSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions).apply {
			setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
		}
		binding.sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
				filterAndSortApps()
			}
			override fun onNothingSelected(parent: AdapterView<*>) {}
		}
		binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
			override fun onQueryTextSubmit(query: String?): Boolean {
				currentQuery = query.orEmpty()
				filterAndSortApps()
				return true
			}
			override fun onQueryTextChange(newText: String?): Boolean {
				currentQuery = newText.orEmpty()
				filterAndSortApps()
				return true
			}
		})
	}

	private fun observeViewModel() {
		configurationViewModel.telegramToken.observe(viewLifecycleOwner) {
			if (binding.telegramTokenInput.text.toString() != it) binding.telegramTokenInput.setText(it)
		}
		configurationViewModel.userId.observe(viewLifecycleOwner) {
			if (binding.whoToMessageID.text.toString() != it) binding.whoToMessageID.setText(it)
		}
		binding.telegramTokenInput.addTextChangedListener(SimpleTextWatcher { configurationViewModel.saveTelegramToken(it) })
		binding.whoToMessageID.addTextChangedListener(SimpleTextWatcher { configurationViewModel.saveUserId(it) })
	}

	private fun loadApps() {
		showLoading(true)
		viewLifecycleOwner.lifecycleScope.launch {
			val pm = requireContext().packageManager
			val newInstalledApps = withContext(Dispatchers.IO) {
				pm.getInstalledPackages(PackageManager.GET_META_DATA).mapNotNull { packageInfo ->
					val appInfo = packageInfo.applicationInfo
					if (appInfo != null) {
						AppItem(
							appName = appInfo.loadLabel(pm).toString(),
							packageName = appInfo.packageName,
							isEnabled = configurationViewModel.getAppEnabled(appInfo.packageName)
						)
					} else {
						null
					}
				}
			}
			installedApps = newInstalledApps
			filterAndSortApps()
		}
	}

	private fun populateAppList(apps: List<AppItem>) {
		appAdapter.updateData(apps)
	}

	private fun showLoading(isLoading: Boolean) {
		binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
	}

	private fun filterAndSortApps() {
		filterJob?.cancel()
		val query = currentQuery
		val sortPosition = binding.sortSpinner.selectedItemPosition
		filterJob = viewLifecycleOwner.lifecycleScope.launch {
			showLoading(true)
			val result = withContext(Dispatchers.Default) {
				val filtered = installedApps.filter {
					it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
				}
				when (sortPosition) {
					0 -> filtered.sortedWith(compareByDescending<AppItem> { it.isEnabled }.thenBy { it.appName.lowercase() })
					1 -> filtered.sortedBy { it.appName.lowercase() }
					2 -> filtered.sortedByDescending { it.appName.lowercase() }
					else -> filtered
				}
			}
			filteredApps = result
			populateAppList(result)
			showLoading(false)
		}
	}

	override fun onDestroyView() {
		filterJob?.cancel()
		_binding = null
		super.onDestroyView()
	}
}

class SimpleTextWatcher(private val onTextChanged: (String) -> Unit) : TextWatcher {
	override fun afterTextChanged(s: Editable?) {
		s?.toString()?.let(onTextChanged)
	}
	override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
	override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
}

data class AppItem(
	val appName: String,
	val packageName: String,
	var isEnabled: Boolean
)

class AppListAdapter(
	private var apps: List<AppItem>,
	private val onToggleEnabled: (String, Boolean) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

	class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
		val appName: TextView = view.findViewById(R.id.appName)
		val appPackageName: TextView = view.findViewById(R.id.appPackageName)
		val appCheckbox: CheckBox = view.findViewById(R.id.appCheckbox)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = apps[position]
		holder.appName.text = item.appName
		holder.appPackageName.text = item.packageName
		holder.appCheckbox.setOnCheckedChangeListener(null)
		holder.appCheckbox.isChecked = item.isEnabled
		holder.appCheckbox.setOnCheckedChangeListener { _, isChecked ->
			item.isEnabled = isChecked
			onToggleEnabled(item.packageName, isChecked)
		}
	}

	override fun getItemCount() = apps.size

	fun updateData(newApps: List<AppItem>) {
		val oldApps = apps
		val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
			object : androidx.recyclerview.widget.DiffUtil.Callback() {
				override fun getOldListSize() = oldApps.size
				override fun getNewListSize() = newApps.size
				override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
					return oldApps[oldItemPosition].packageName == newApps[newItemPosition].packageName
				}
				override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
					return oldApps[oldItemPosition] == newApps[newItemPosition]
				}
			}
		)
		apps = newApps
		diff.dispatchUpdatesTo(this)
	}
}
