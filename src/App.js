import React, { useState } from 'react';
import { AppBar, Box, Button, createTheme, CssBaseline, FormControl, Grid, makeStyles, Modal, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, ThemeProvider, Toolbar, Typography } from '@material-ui/core';
import { useForm, Controller } from 'react-hook-form';

import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';
import 'firebase/compat/auth';

import { useAuthState } from 'react-firebase-hooks/auth';
import { useCollectionData } from 'react-firebase-hooks/firestore';
import SignIn from './components/SignIn';
import AccountSignedIn from './components/AccountSignedIn';
import PlantOptions from './components/PlantOptions';

firebase.initializeApp({
  apiKey: "AIzaSyAtQtSFrmXIylCOktouGw2YsnKwGgv6U3k",
  authDomain: "plant0.firebaseapp.com",
  projectId: "plant0",
  storageBucket: "plant0.appspot.com",
  messagingSenderId: "438322523913",
  appId: "1:438322523913:web:8258be948ab81d86616dd8"
});

const darkTheme = createTheme({
  palette: {
    type: 'dark',
  }
});

const useStyles = makeStyles(() => ({
  mainLayoutGrid: {
    minHeight: "95vh",
    width: "100vw",
    maxWidth: "100%"
  },
  verticalMarginGrid: {
    minHeight: "10%",
    maxHeight: "10%"
  },
  contentGrid: {
    minHeight: "80%"
  }, 
  addPlantModal: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    width: 400,
    bgcolor: 'background.paper',
    border: '2px solid #000',
    boxShadow: 24,
    p: 4
  }
}));

function plantSorter() {
  return function (a, b) { 
    if (a.nextWatering === b.nextWatering)
      return 0;
    else if (!a.nextWatering) {
      return -1;
    } else if (!b.nextWatering) {
      return 1;
    } else { 
      return a.nextWatering - b.nextWatering
    }
  }
}

const auth = firebase.auth();
const firestore = firebase.firestore();

function App() {
  const classes = useStyles();
  const [user] = useAuthState(auth);
  const plantsRef = firestore.collection("plants");
  const query = plantsRef.where("uid", "==", user ? auth.currentUser.uid : '');
  const [plants] = useCollectionData(query, { idField: 'id' });
  const [openAddPlant, setOpenAddPlant] = useState(false);
  const { handleSubmit, control, reset } = useForm();

  var content;

  const handleWater = (plant) => {
    var newDateForWatering = new Date(new Date().getTime() + (plant.wateringGap * (1000 * 3600 * 24)));
    plantsRef.doc(plant.id).update({ lastWatering: new Date(), nextWatering: newDateForWatering });
  }

  const onSubmit = (form) => {
    plantsRef.add({
      plantName: form.plantName,
      wateringGap: form.daysBetweenWater,
      uid: auth.currentUser.uid
    });
    setOpenAddPlant(false);
    reset();
  }

  const modalStyle = {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    width: 400,
    bgcolor: 'background.paper',
    border: '2px solid #000',
    boxShadow: 24,
    p: 4,
  };

  if(user) {
    content = <TableContainer component={Paper} style={{ marginBottom: "40px" }}>
    <Table sx={{ minWidth: 650}} aria-label="plant table">
      <TableHead>
        <TableRow>
          <TableCell>Plant Name</TableCell>
          <TableCell>Last Watered Date</TableCell>
          <TableCell>Next Watering Date</TableCell>
          <TableCell></TableCell>
          <TableCell></TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {
          plants?.sort(plantSorter()).map(plant => (
            <TableRow 
              key={plant.id} 
              sx={{ '&:last-child td, &:last-child th': { border: 0 } }} 
              style={{ backgroundColor: !plant.nextWatering ? 'rgb(255, 214, 0, 0.4)' : new Date().getTime() > plant.nextWatering.toDate().getTime() ? 'rgb(255, 0, 0, 0.5)' : ''}}
            >
              <TableCell scope="plant">{plant.plantName}</TableCell>
              <TableCell>{plant?.lastWatering ? plant.lastWatering.toDate().toString() : "This plant has not been watered yet"}</TableCell>
              <TableCell>{plant?.nextWatering ? plant.nextWatering.toDate().toString() : "This plant has not been watered yet"}</TableCell>
              <TableCell>
                <Button variant="contained" onClick={() => handleWater(plant)}>
                  Water This Plant
                </Button>
              </TableCell>
              <TableCell>
                <PlantOptions plant={plant} plantsRef={plantsRef} />
              </TableCell>
            </TableRow>
          ))
        }
        <TableRow style={{ padding: "5px", alignItems: "center"}}>
          <TableCell />
          <TableCell />
          <TableCell />
          <TableCell>
            <Button variant="contained" onClick={() => setOpenAddPlant(!openAddPlant)}>Add New Plant</Button>
            <Modal
              open={openAddPlant}
              onClose={() => setOpenAddPlant(false)}
            >
              <Box sx={modalStyle}>
                  <form onSubmit={handleSubmit(onSubmit)}>
                    
                <FormControl>
                  <Controller
                      name="plantName"
                      control={control}
                      defaultValue=""
                      render={({ field: { onChange, value }}) => (
                        <TextField
                          required
                          label="Plant Name"
                          variant="outlined"
                          value={value}
                          onChange={onChange}
                          margin="normal"
                        />
                      )}
                      />
                      <Controller
                      name="daysBetweenWater"
                      control={control}
                      defaultValue=""
                      render={({ field: { onChange, value } }) => (
                        <TextField
                          required
                          label="Days Between Water"
                          type="number"
                          variant="outlined"
                          value={value}
                          onChange={onChange}
                          margin="normal"
                          InputProps={{
                            inputProps: {
                              min: 1
                            }
                          }}
                        />
                      )}
                    />
                      <Button
                        variant="contained"
                        type="submit"
                        >Add Plant
                      </Button>
                </FormControl>
              </form>
              </Box>
            </Modal>
            </TableCell>
            <TableCell/>
        </TableRow>
      </TableBody>
    </Table>
  </TableContainer>
  }

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <div class="page-bg" />
      <Box sx={{ flexGrow: 1 }}>
        <AppBar position="static" style={{ background: "#127A42", marginBottom: "60px"}}>
          <Toolbar>
            <Typography>Plant Water Reminder</Typography>
            <div style={{ flexGrow: 1}}/>
            {user ? <AccountSignedIn auth={auth} firebase={firebase}/> : <SignIn auth={auth} firebase={firebase}/>}
          </Toolbar>
        </AppBar>
      </Box>
      <Grid container className={classes.mainLayoutGrid}>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={8} className={classes.contentGrid}>
          {content}
        </Grid>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
      </Grid>
    </ThemeProvider>
  );
}

export default App;
