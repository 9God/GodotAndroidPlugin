extends Node2D

# TODO: Update to match your plugin's name
var _plugin_name = "GodotAndroidPlugin"
var _android_plugin
var _camera_type = 0

func _ready():
	if Engine.has_singleton(_plugin_name):
		_android_plugin = Engine.get_singleton(_plugin_name)
		var instanceId = get_instance_id()
		_android_plugin.setCallbackWithSoloStringParam(instanceId, "on_android_plugin_callback")
	else:
		printerr("Couldn't find plugin " + _plugin_name)
		
func _process(delta: float) -> void:
	if _android_plugin:
		var debug_text = $Label
		# TODO: Update to match your plugin's API
		var hw = _android_plugin.getCameraHW()
		var w = hw[0]
		var h = hw[1]
		if w > 0 && h > 0:
			var frame_data = _android_plugin.getCameraRGBA8888Frame()
			var ss = frame_data.size()
			if ss > 0:
				debug_text.text = "frame data获取了"
				# 创建一个texture
				var image = Image.create_from_data(w, h, false, Image.FORMAT_RGBA8, frame_data)
				debug_text.text = "image创建了"
				var tex = ImageTexture.create_from_image(image)
				debug_text.text = "create_from_image"
				var texture_rect = $TextureRect
				texture_rect.texture = tex
				debug_text.text = "tex: %d x %d; %d" % [w, h, ss]
			
func _on_Button_pressed():
	if _android_plugin:
		# TODO: Update to match your plugin's API
		# _android_plugin.startCamera(0)
		_android_plugin.selectFromAlbum()
		
func _on_Button1_pressed():
	if _android_plugin:
		var debug_text = $Label
		# TODO: Update to match your plugin's API
		var hw = _android_plugin.getAlbumImageHW()
		var w = hw[0]
		var h = hw[1]
		if w > 0 && h > 0:
			var frame_data = _android_plugin.getAlbumImageRGBA8888()
			var ss = frame_data.size()
			if ss > 0:
				debug_text.text = "frame data获取了"
				# 创建一个texture
				var image = Image.create_from_data(w, h, false, Image.FORMAT_RGBA8, frame_data)
				# image.resize(1920, 1080)
				debug_text.text = "image创建了"
				var tex = ImageTexture.create_from_image(image)
				debug_text.text = "create_from_image"
				var texture_rect = $TextureRect
				texture_rect.texture = tex
				debug_text.text = "tex: %d x %d; %d" % [w, h, ss]
		
func _on_Button2_pressed():
	if _android_plugin:
		# TODO: Update to match your plugin's API
		_android_plugin.stopCamera()
		
func on_android_plugin_callback(msg: String):
	var debug_text = $Label
	debug_text.text = msg
